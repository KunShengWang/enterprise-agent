package com.agent.platform.guardrail;

public interface PromptInjectionDetector {

    /**
     * 检测用户输入是否是不安全的操作
     */
    GuardrailDecision detect(String input);
}
