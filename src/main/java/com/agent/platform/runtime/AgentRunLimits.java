package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;

/**
 * 一次 Agent Run 的硬性资源边界。
 */
public record AgentRunLimits(
        int maxTurns,// 最大轮次
        int maxModelCalls,// 最大模型调用次数
        int maxToolCalls,// 最大工具调用次数
        long maxInputTokens,// 最大输入 token
        long maxOutputTokens,// 最大输出 token
        double maxEstimatedCost,// 最大预估成本
        long maxRunDurationMillis// 最大运行时间（毫秒）
) {

    public AgentRunLimits {
        maxTurns = Math.max(1, maxTurns);
        maxModelCalls = Math.max(1, maxModelCalls);
        maxToolCalls = Math.max(0, maxToolCalls);
        maxInputTokens = Math.max(1, maxInputTokens);
        maxOutputTokens = Math.max(1, maxOutputTokens);
        maxEstimatedCost = Math.max(0, maxEstimatedCost);
        maxRunDurationMillis = Math.max(1_000, maxRunDurationMillis);
    }

    public static AgentRunLimits from(AgentProperties properties) {
        return new AgentRunLimits(
                properties.getMaxTurnsPerRun(),
                properties.getMaxModelCallsPerRun(),
                properties.getMaxToolCallsPerRun(),
                properties.getMaxInputTokensPerRun(),
                properties.getMaxOutputTokensPerRun(),
                properties.getMaxEstimatedCostPerRun(),
                properties.getMaxRunDurationMillis()
        );
    }
}
