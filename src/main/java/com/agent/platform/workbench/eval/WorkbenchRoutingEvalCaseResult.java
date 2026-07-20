package com.agent.platform.workbench.eval;

import com.agent.platform.workbench.model.RouteDisposition;
import com.agent.platform.workbench.model.WorkCommandType;

public record WorkbenchRoutingEvalCaseResult(
        String caseId,
        String category,
        boolean passed,
        boolean ambiguousOrAdversarial,
        WorkCommandType expectedCommand,
        WorkCommandType actualCommand,
        String expectedTarget,
        String actualTarget,
        RouteDisposition expectedDisposition,
        RouteDisposition actualDisposition,
        boolean dangerousMisroute,
        boolean dangerousCommandMisclassification,
        boolean wrongFocus,
        boolean identifierSourceViolation,
        boolean hiddenTargetSelected,
        long promptTokens,
        long completionTokens,
        long latencyMs,
        String detail
) {
}
