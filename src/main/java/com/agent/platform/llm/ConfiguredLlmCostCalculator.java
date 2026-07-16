package com.agent.platform.llm;

import com.agent.platform.config.AgentProperties;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredLlmCostCalculator implements LlmCostCalculator {

    private static final double TOKENS_PER_MILLION = 1_000_000d;

    private final AgentProperties properties;

    public ConfiguredLlmCostCalculator(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public double estimate(LlmUsage usage) {
        if (usage == null) {
            return 0;
        }
        // 从配置读价格（每百万 token 多少钱）
        AgentProperties.ModelPricing pricing = properties.getModelPricing();
        long cacheRead = Math.max(0, usage.cacheReadInputTokens());
        long cacheWrite = Math.max(0, usage.cacheWriteInputTokens());
        long regularInput = Math.max(0, usage.promptTokens() - cacheRead - cacheWrite);
        long output = Math.max(0, usage.completionTokens());
        return regularInput * pricing.getInputPerMillionTokens() / TOKENS_PER_MILLION
                + output * pricing.getOutputPerMillionTokens() / TOKENS_PER_MILLION
                + cacheRead * pricing.getCacheReadPerMillionTokens() / TOKENS_PER_MILLION
                + cacheWrite * pricing.getCacheWritePerMillionTokens() / TOKENS_PER_MILLION;
    }
}
