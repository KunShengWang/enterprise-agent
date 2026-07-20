package com.agent.platform.workbench.eval;

import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.target.ExecutionTargetId;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchRoutingEvalSuiteTests {

    private final WorkbenchRoutingEvalSuite suite = new WorkbenchRoutingEvalSuite();

    @Test
    void freezesCoverageRequiredByM1E() {
        var cases = suite.cases();

        assertTrue(cases.size() >= 80);
        assertTrue(cases.stream().filter(WorkbenchRoutingEvalCase::ambiguousOrAdversarial).count() >= 30);
        assertTrue(cases.stream().filter(item -> item.kind() == WorkbenchEvalCaseKind.COMMAND).count() >= 20);
        assertTrue(cases.stream().filter(item -> item.kind() == WorkbenchEvalCaseKind.ROUTE).count() >= 60);
        assertTrue(cases.stream().filter(item -> item.kind() == WorkbenchEvalCaseKind.ROUTE
                && item.expectedTarget() != ExecutionTargetId.GENERAL_AGENT
                && item.expectedDisposition() != RouteDisposition.REQUIRE_CLARIFICATION).count() >= 30);
        Set<ExecutionTargetId> targets = cases.stream()
                .filter(item -> item.kind() == WorkbenchEvalCaseKind.ROUTE)
                .map(WorkbenchRoutingEvalCase::expectedTarget)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                ExecutionTargetId.GENERAL_AGENT,
                ExecutionTargetId.ORDERCARE_CASE,
                ExecutionTargetId.INCIDENT_INVESTIGATION,
                ExecutionTargetId.INCIDENT_RECOVERY_PLAN), targets);
        for (ExecutionTargetId target : targets) {
            assertTrue(cases.stream().filter(item -> target == item.expectedTarget()).count() >= 3,
                    () -> target + " needs at least three cases");
        }
        assertTrue(cases.stream().anyMatch(item -> item.expectedDisposition() == RouteDisposition.REJECT
                || item.expectedDisposition() == RouteDisposition.REQUIRE_CLARIFICATION));
    }

    @Test
    void caseIdsAreStableAndUnique() {
        var cases = suite.cases();
        assertEquals(cases.size(), cases.stream().map(WorkbenchRoutingEvalCase::caseId).distinct().count());
        assertTrue(cases.stream().allMatch(item -> item.caseId().matches("[a-z0-9-]+")));
    }
}
