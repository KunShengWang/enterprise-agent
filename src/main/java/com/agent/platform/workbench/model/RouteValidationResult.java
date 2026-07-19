package com.agent.platform.workbench.model;

import java.util.List;

public record RouteValidationResult(
        RouteDisposition disposition,
        ValidatedExecutionInput validatedInput,
        List<String> reasons,
        String failureCode
) {
    public RouteValidationResult {
        if (disposition == null) {
            throw new IllegalArgumentException("disposition must not be null");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        failureCode = failureCode == null ? "" : failureCode.trim();
    }
}

