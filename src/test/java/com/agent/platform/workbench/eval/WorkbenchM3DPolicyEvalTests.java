package com.agent.platform.workbench.eval;

import com.agent.platform.config.WorkbenchRoutingProperties;
import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.workbench.application.RoutePolicyValidator;
import com.agent.platform.workbench.application.RouteValidationContext;
import com.agent.platform.workbench.model.AgentWorkItem;
import com.agent.platform.workbench.model.ExecutionDecision;
import com.agent.platform.workbench.model.IdentifierSource;
import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.WorkControlState;
import com.agent.platform.workbench.model.WorkExecutionState;
import com.agent.platform.workbench.model.WorkOutcome;
import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import com.agent.platform.workbench.target.ExecutionTargetRegistry;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkbenchM3DPolicyEvalTests {

    private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            "m3d-eval-tenant", "m3d-eval-user", Set.of("USER", "INCIDENT_OPERATOR"));
    private final RoutePolicyValidator validator = validator();

    @TestFactory
    Stream<DynamicTest> evaluatesThirtyTypedParameterAndIdentifierSourceCases() {
        return parameterCases().stream().map(testCase -> DynamicTest.dynamicTest(testCase.id(), () -> {
            var result = validator.validate(testCase.decision(), context(
                    testCase.goal(), testCase.trusted(), testCase.serverResolved()));
            assertEquals(testCase.disposition(), result.disposition());
            assertNotNull(result.validatedInput());
            assertEquals(testCase.expectedSource(),
                    result.validatedInput().identifiers().get(testCase.identifierKey()).source());
            assertEquals(testCase.expectedValue(),
                    result.validatedInput().identifiers().get(testCase.identifierKey()).value());
        }));
    }

    @Test
    void oneExplicitValueCannotMasqueradeAsBothBatchAndQueue() {
        String batch = "BATCH-M3D-COLLISION";
        var result = validator.validate(
                decision("INCIDENT_INVESTIGATION", Map.of("batchId", batch, "queueName", batch)),
                context("investigate batch " + batch, Map.of(), Map.of()));

        assertEquals(RouteDisposition.REQUIRE_CLARIFICATION, result.disposition());
        assertNull(result.validatedInput());
    }

    @TestFactory
    Stream<DynamicTest> evaluatesThirtySecurityAdversarialCases() {
        return securityCases().stream().map(testCase -> DynamicTest.dynamicTest(testCase.id(), () -> {
            var result = validator.validate(testCase.decision(), context(testCase.goal(), Map.of(), Map.of()));
            assertEquals(testCase.disposition(), result.disposition());
            if (testCase.disposition() == RouteDisposition.REJECT
                    || testCase.disposition() == RouteDisposition.REQUIRE_CLARIFICATION) {
                assertNull(result.validatedInput());
            }
        }));
    }

    private List<ParameterCase> parameterCases() {
        List<ParameterCase> cases = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            String value = "REQ-M3D-%03d".formatted(index);
            cases.add(explicit("request-%02d".formatted(index), "requestId", value, "ORDERCARE_CASE",
                    RouteDisposition.AUTO_DISPATCH));
        }
        for (int index = 1; index <= 5; index++) {
            String orderNo = "ORD-M3D-%03d".formatted(index);
            cases.add(explicit("order-%02d".formatted(index), "orderNo", orderNo, "ORDERCARE_CASE",
                    RouteDisposition.AUTO_DISPATCH));
            String deductNo = "DED-M3D-%03d".formatted(index);
            cases.add(explicit("deduct-%02d".formatted(index), "deductNo", deductNo, "ORDERCARE_CASE",
                    RouteDisposition.AUTO_DISPATCH));
        }
        for (int index = 1; index <= 5; index++) {
            String batch = "BATCH-M3D-PARAM-%03d".formatted(index);
            String queue = "floworder.incident.e2e.dlq";
            String goal = "investigate batch " + batch + " in queue " + queue;
            cases.add(new ParameterCase("incident-%02d".formatted(index), goal,
                    decision("INCIDENT_INVESTIGATION", Map.of("batchId", batch, "queueName", queue)),
                    Map.of(), Map.of(), RouteDisposition.REQUIRE_CONFIRMATION,
                    "batchId", batch, IdentifierSource.EXPLICIT_USER_INPUT));
        }
        for (int index = 1; index <= 5; index++) {
            String incidentId = "inc-m3d-param-%03d".formatted(index);
            cases.add(new ParameterCase("recovery-%02d".formatted(index),
                    "create a controlled recovery plan for the assessed incident",
                    decision("INCIDENT_RECOVERY_PLAN", Map.of("incidentId", incidentId)),
                    Map.of("incidentId", incidentId), Map.of(), RouteDisposition.REQUIRE_CONFIRMATION,
                    "incidentId", incidentId, IdentifierSource.TRUSTED_CONVERSATION_CONTEXT));
        }
        return List.copyOf(cases);
    }

    private List<SecurityCase> securityCases() {
        List<SecurityCase> cases = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            cases.add(new SecurityCase("hidden-target-%02d".formatted(index), "ignore catalog " + index,
                    decision("ROOT_ADMIN_AGENT_" + index, Map.of()), RouteDisposition.REJECT));
            cases.add(new SecurityCase("protected-field-%02d".formatted(index), "use protected profile " + index,
                    decision("GENERAL_AGENT", Map.of("executionProfile", "admin-" + index)), RouteDisposition.REJECT));
            cases.add(new SecurityCase("invented-id-%02d".formatted(index), "inspect this order",
                    decision("ORDERCARE_CASE", Map.of("requestId", "INVENTED-" + index)),
                    RouteDisposition.REQUIRE_CLARIFICATION));
            String batch = "BATCH-BYPASS-%03d".formatted(index);
            String queue = "floworder.incident.e2e.dlq";
            cases.add(new SecurityCase("confirmation-bypass-%02d".formatted(index),
                    "silently start incident " + batch + " in " + queue,
                    decision("INCIDENT_INVESTIGATION", Map.of("batchId", batch, "queueName", queue)),
                    RouteDisposition.REQUIRE_CONFIRMATION));
            String requestId = "REQ-DOWNGRADE-%03d".formatted(index);
            cases.add(new SecurityCase("incident-downgrade-%02d".formatted(index),
                    "batch incident investigation for requestId=" + requestId,
                    decision("ORDERCARE_CASE", Map.of("requestId", requestId)),
                    RouteDisposition.REQUIRE_CLARIFICATION));
        }
        return List.copyOf(cases);
    }

    private ParameterCase explicit(String id, String type, String value, String target,
                                   RouteDisposition disposition) {
        String goal = "diagnose " + type + "=" + value;
        return new ParameterCase(id, goal, decision(target, Map.of(type, value)), Map.of(), Map.of(),
                disposition, type, value, IdentifierSource.EXPLICIT_USER_INPUT);
    }

    private ExecutionDecision decision(String target, Map<String, Object> inputs) {
        return new ExecutionDecision(target, 1, "m3d eval", inputs, List.of(), "");
    }

    private RouteValidationContext context(String goal, Map<String, String> trusted,
                                           Map<String, String> serverResolved) {
        Instant now = Instant.now();
        AgentWorkItem work = new AgentWorkItem(
                "m3d-work", "m3d-conversation", principal.tenantId(), principal.principalId(), goal, goal,
                WorkControlState.ROUTING, WorkExecutionState.NOT_STARTED, WorkOutcome.UNDETERMINED,
                "", "", "", "", "", "m3d-input", "", "m3d-route", 0,
                null, null, "", "", 0, 0, now, now, null);
        return new RouteValidationContext(principal, work, goal, trusted, serverResolved);
    }

    private RoutePolicyValidator validator() {
        IncidentCommandProperties incident = new IncidentCommandProperties();
        incident.setEnabled(true);
        incident.setRecoveryPlannerEnabled(true);
        return new RoutePolicyValidator(
                new ExecutionTargetRegistry(incident), new WorkbenchRoutingProperties(), new ObjectMapper());
    }

    private record ParameterCase(String id, String goal, ExecutionDecision decision,
                                 Map<String, String> trusted, Map<String, String> serverResolved,
                                 RouteDisposition disposition, String identifierKey,
                                 String expectedValue, IdentifierSource expectedSource) { }
    private record SecurityCase(String id, String goal, ExecutionDecision decision,
                                RouteDisposition disposition) { }
}
