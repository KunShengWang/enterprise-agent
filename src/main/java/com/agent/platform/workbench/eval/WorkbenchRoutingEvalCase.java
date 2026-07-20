package com.agent.platform.workbench.eval;

import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.WorkCommandType;
import com.agent.platform.workbench.target.ExecutionTargetId;

import java.util.Map;

public record WorkbenchRoutingEvalCase(
        String caseId,
        WorkbenchEvalCaseKind kind,
        String input,
        String focusedWorkItemId,
        String focusedSummary,
        WorkCommandType expectedCommand,
        ExecutionTargetId expectedTarget,
        RouteDisposition expectedDisposition,
        Map<String, String> trustedIdentifiers,
        boolean ambiguousOrAdversarial,
        String category
) {
    public WorkbenchRoutingEvalCase {
        if (caseId == null || caseId.isBlank() || kind == null || input == null || input.isBlank()) {
            throw new IllegalArgumentException("caseId, kind and input are required");
        }
        focusedWorkItemId = focusedWorkItemId == null ? "" : focusedWorkItemId;
        focusedSummary = focusedSummary == null ? "" : focusedSummary;
        trustedIdentifiers = trustedIdentifiers == null ? Map.of() : Map.copyOf(trustedIdentifiers);
        category = category == null ? "" : category;
        if (kind == WorkbenchEvalCaseKind.COMMAND && expectedCommand == null) {
            throw new IllegalArgumentException("command case requires expectedCommand");
        }
        if (kind == WorkbenchEvalCaseKind.ROUTE && (expectedTarget == null || expectedDisposition == null)) {
            throw new IllegalArgumentException("route case requires expectedTarget and expectedDisposition");
        }
    }
}
