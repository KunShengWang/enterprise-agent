package com.agent.platform.guardrail;

public record GuardrailDecision(
        GuardrailStage stage,
        GuardrailAction action,
        String reason,
        String safeContent
) {

    public static GuardrailDecision allow(GuardrailStage stage, String reason) {
        return new GuardrailDecision(stage, GuardrailAction.ALLOW, reason, null);
    }

    public static GuardrailDecision block(GuardrailStage stage, String reason) {
        return new GuardrailDecision(stage, GuardrailAction.BLOCK, reason, null);
    }

    public static GuardrailDecision requireApproval(GuardrailStage stage, String reason) {
        return new GuardrailDecision(stage, GuardrailAction.REQUIRE_APPROVAL, reason, null);
    }

    public static GuardrailDecision redact(GuardrailStage stage, String reason, String safeContent) {
        return new GuardrailDecision(stage, GuardrailAction.REDACT, reason, safeContent);
    }
}
