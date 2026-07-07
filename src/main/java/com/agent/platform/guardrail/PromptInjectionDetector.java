package com.agent.platform.guardrail;

public interface PromptInjectionDetector {

    GuardrailDecision detect(String input);
}
