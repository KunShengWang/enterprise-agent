package com.agent.platform.llm;

public interface LlmCostCalculator {

    double estimate(LlmUsage usage);
}
