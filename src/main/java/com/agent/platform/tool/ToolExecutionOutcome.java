package com.agent.platform.tool;

/** Runtime semantics; the existing model-visible success/content/error envelope is retained. */
public enum ToolExecutionOutcome {
    SUCCESS, VALIDATION_ERROR, BUSINESS_ERROR, RETRYABLE_ERROR,
    POLICY_DENIED, APPROVAL_REQUIRED, FATAL_ERROR;

    public static ToolExecutionOutcome from(ToolCallResult result) {
        if (result == null) return FATAL_ERROR;
        if (Boolean.TRUE.equals(result.metadata().get("manualReview"))) return FATAL_ERROR;
        if (result.success()) return SUCCESS;
        Object explicit = result.metadata().get("_harnessOutcome");
        if (explicit != null) {
            try { return valueOf(explicit.toString()); }
            catch (IllegalArgumentException ignored) { return FATAL_ERROR; }
        }
        String errorType = String.valueOf(result.metadata().get("errorType"));
        if ("failed".equals(result.metadata().get("validation"))
                || "UNKNOWN_CAPABILITY".equals(errorType)) return VALIDATION_ERROR;
        if ("BLOCK".equals(result.metadata().get("policyAction"))
                || errorType.equals("CAPABILITY_NOT_ALLOWED")
                || errorType.equals("CAPABILITY_NOT_AVAILABLE_IN_PHASE")
                || errorType.equals("SINGLE_USE_CAPABILITY_REPEATED")) return POLICY_DENIED;
        // Transport-neutral structured codes; no business-domain types or exception-message parsing.
        String code = String.valueOf(result.metadata().getOrDefault("_harnessErrorCode",
                result.metadata().getOrDefault("errorCode", errorType))).toUpperCase(java.util.Locale.ROOT);
        Object httpStatus = result.metadata().getOrDefault("httpStatus", result.metadata().get("statusCode"));
        if (code.equals("TIMEOUT") || code.endsWith("_TIMEOUT")
                || code.equals("UNAVAILABLE") || code.endsWith("_UNAVAILABLE")
                || (httpStatus instanceof Number n && (n.intValue() == 429 || n.intValue() >= 500 && n.intValue() <= 599)))
            return RETRYABLE_ERROR;
        if (Boolean.TRUE.equals(result.metadata().get("retryable"))) return RETRYABLE_ERROR;
        return BUSINESS_ERROR;
    }
}
