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
}
