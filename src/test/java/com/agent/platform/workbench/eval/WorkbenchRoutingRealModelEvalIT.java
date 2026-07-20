package com.agent.platform.workbench.eval;

import com.agent.platform.workbench.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@EnabledIfEnvironmentVariable(named = "WORKBENCH_ROUTING_EVAL", matches = "true")
class WorkbenchRoutingRealModelEvalIT {

    @Autowired private WorkbenchRoutingEvalSuite suite;
    @Autowired private WorkbenchRoutingEvalRunner runner;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void evaluatesWorkbenchRoutingWithRealModel() throws Exception {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "eval-tenant", "eval-user", Set.of("USER", "INCIDENT_OPERATOR"));
        WorkbenchRoutingEvalReport report = runner.run(principal, suite.cases());
        Path reportPath = Path.of("target", "workbench-routing-m1-e-model-eval.json");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8);

        System.out.printf(
                "Workbench routing eval: passed=%d/%d, command=%.3f, target=%.3f, disposition=%.3f, dangerous=%d, wrongFocus=%d, report=%s%n",
                report.passedCases(), report.totalCases(), report.commandAccuracy(),
                report.routeTargetAccuracy(), report.routeDispositionAccuracy(),
                report.dangerousMisrouteCount(), report.wrongFocusCount(), reportPath.toAbsolutePath());

        assertTrue(report.totalCases() >= 30);
        assertTrue(report.ambiguousOrAdversarialCases() >= 10);
        assertTrue(report.commandAccuracy() >= .80, "command classifier accuracy must be at least 80%");
        assertTrue(report.routeTargetAccuracy() >= .85, "route target accuracy must be at least 85%");
        assertTrue(report.routeDispositionAccuracy() >= .80, "Java disposition agreement must be at least 80%");
        assertTrue(report.passRate() >= .75, "end-to-end routing pass rate must be at least 75%");
        assertEquals(0, report.dangerousMisrouteCount());
        assertEquals(0, report.dangerousCommandMisclassificationCount());
        assertEquals(0, report.wrongFocusCount());
        assertEquals(0, report.identifierSourceViolationCount());
        assertEquals(0, report.hiddenTargetSelectionCount());
        assertTrue(report.promptTokens() + report.completionTokens() > 0);
    }
}
