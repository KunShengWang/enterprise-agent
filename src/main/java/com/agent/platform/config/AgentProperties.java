package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent")
public class AgentProperties {

    private boolean mockMode = false;

    private int maxTurnsPerRun = 12;

    private int maxModelCallsPerRun = 8;

    private int maxToolCallsPerRun = 6;

    private long maxInputTokensPerRun = 24_000;

    private long maxOutputTokensPerRun = 8_000;

    private long modelContextWindowTokens = 32_000;

    private long contextOutputReserveTokens = 2_000;

    private long contextSafetyMarginTokens = 1_000;

    private int contextSummaryMaxTokens = 1_500;

    private int maxContextOverflowRetries = 1;

    private double maxEstimatedCostPerRun = 0;

    private ModelPricing modelPricing = new ModelPricing();

    private long maxRunDurationMillis = 120_000;

    private int maxToolExecutionAttempts = 2;

    private long toolRetryBackoffMillis = 150;

    private boolean replanAfterToolFailure = true;

    private int streamBackpressureBufferSize = 256;

    private int streamHeartbeatSeconds = 10;

    private int maxToolResultCharsForModel = 12_000;

    private int maxToolErrorCharsForModel = 2_000;

    private String defaultSystemPrompt = "";

    public boolean isMockMode() {
        return mockMode;
    }

    public void setMockMode(boolean mockMode) {
        this.mockMode = mockMode;
    }

    public int getMaxModelCallsPerRun() {
        return maxModelCallsPerRun;
    }

    public int getMaxTurnsPerRun() {
        return maxTurnsPerRun;
    }

    public void setMaxTurnsPerRun(int maxTurnsPerRun) {
        this.maxTurnsPerRun = maxTurnsPerRun;
    }

    public void setMaxModelCallsPerRun(int maxModelCallsPerRun) {
        this.maxModelCallsPerRun = maxModelCallsPerRun;
    }

    public int getMaxToolCallsPerRun() {
        return maxToolCallsPerRun;
    }

    public void setMaxToolCallsPerRun(int maxToolCallsPerRun) {
        this.maxToolCallsPerRun = maxToolCallsPerRun;
    }

    public long getMaxInputTokensPerRun() {
        return maxInputTokensPerRun;
    }

    public void setMaxInputTokensPerRun(long maxInputTokensPerRun) {
        this.maxInputTokensPerRun = maxInputTokensPerRun;
    }

    public long getMaxOutputTokensPerRun() {
        return maxOutputTokensPerRun;
    }

    public void setMaxOutputTokensPerRun(long maxOutputTokensPerRun) {
        this.maxOutputTokensPerRun = maxOutputTokensPerRun;
    }

    public long getModelContextWindowTokens() {
        return modelContextWindowTokens;
    }

    public void setModelContextWindowTokens(long modelContextWindowTokens) {
        this.modelContextWindowTokens = modelContextWindowTokens;
    }

    public long getContextOutputReserveTokens() {
        return contextOutputReserveTokens;
    }

    public void setContextOutputReserveTokens(long contextOutputReserveTokens) {
        this.contextOutputReserveTokens = contextOutputReserveTokens;
    }

    public long getContextSafetyMarginTokens() {
        return contextSafetyMarginTokens;
    }

    public void setContextSafetyMarginTokens(long contextSafetyMarginTokens) {
        this.contextSafetyMarginTokens = contextSafetyMarginTokens;
    }

    public int getContextSummaryMaxTokens() {
        return contextSummaryMaxTokens;
    }

    public void setContextSummaryMaxTokens(int contextSummaryMaxTokens) {
        this.contextSummaryMaxTokens = contextSummaryMaxTokens;
    }

    public int getMaxContextOverflowRetries() {
        return maxContextOverflowRetries;
    }

    public void setMaxContextOverflowRetries(int maxContextOverflowRetries) {
        this.maxContextOverflowRetries = maxContextOverflowRetries;
    }

    public double getMaxEstimatedCostPerRun() {
        return maxEstimatedCostPerRun;
    }

    public void setMaxEstimatedCostPerRun(double maxEstimatedCostPerRun) {
        this.maxEstimatedCostPerRun = maxEstimatedCostPerRun;
    }

    public ModelPricing getModelPricing() {
        return modelPricing;
    }

    public void setModelPricing(ModelPricing modelPricing) {
        this.modelPricing = modelPricing == null ? new ModelPricing() : modelPricing;
    }

    public long getMaxRunDurationMillis() {
        return maxRunDurationMillis;
    }

    public void setMaxRunDurationMillis(long maxRunDurationMillis) {
        this.maxRunDurationMillis = maxRunDurationMillis;
    }

    public int getMaxToolExecutionAttempts() {
        return maxToolExecutionAttempts;
    }

    public void setMaxToolExecutionAttempts(int maxToolExecutionAttempts) {
        this.maxToolExecutionAttempts = maxToolExecutionAttempts;
    }

    public long getToolRetryBackoffMillis() {
        return toolRetryBackoffMillis;
    }

    public void setToolRetryBackoffMillis(long toolRetryBackoffMillis) {
        this.toolRetryBackoffMillis = toolRetryBackoffMillis;
    }

    public boolean isReplanAfterToolFailure() {
        return replanAfterToolFailure;
    }

    public void setReplanAfterToolFailure(boolean replanAfterToolFailure) {
        this.replanAfterToolFailure = replanAfterToolFailure;
    }

    public int getStreamBackpressureBufferSize() {
        return streamBackpressureBufferSize;
    }

    public void setStreamBackpressureBufferSize(int streamBackpressureBufferSize) {
        this.streamBackpressureBufferSize = streamBackpressureBufferSize;
    }

    public int getStreamHeartbeatSeconds() {
        return streamHeartbeatSeconds;
    }

    public void setStreamHeartbeatSeconds(int streamHeartbeatSeconds) {
        this.streamHeartbeatSeconds = streamHeartbeatSeconds;
    }

    public int getMaxToolResultCharsForModel() {
        return maxToolResultCharsForModel;
    }

    public void setMaxToolResultCharsForModel(int maxToolResultCharsForModel) {
        this.maxToolResultCharsForModel = Math.max(512, maxToolResultCharsForModel);
    }

    public int getMaxToolErrorCharsForModel() {
        return maxToolErrorCharsForModel;
    }

    public void setMaxToolErrorCharsForModel(int maxToolErrorCharsForModel) {
        this.maxToolErrorCharsForModel = Math.max(256, maxToolErrorCharsForModel);
    }

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }

    public static class ModelPricing {

        private double inputPerMillionTokens;
        private double outputPerMillionTokens;
        private double cacheReadPerMillionTokens;
        private double cacheWritePerMillionTokens;

        public double getInputPerMillionTokens() {
            return inputPerMillionTokens;
        }

        public void setInputPerMillionTokens(double inputPerMillionTokens) {
            this.inputPerMillionTokens = Math.max(0, inputPerMillionTokens);
        }

        public double getOutputPerMillionTokens() {
            return outputPerMillionTokens;
        }

        public void setOutputPerMillionTokens(double outputPerMillionTokens) {
            this.outputPerMillionTokens = Math.max(0, outputPerMillionTokens);
        }

        public double getCacheReadPerMillionTokens() {
            return cacheReadPerMillionTokens;
        }

        public void setCacheReadPerMillionTokens(double cacheReadPerMillionTokens) {
            this.cacheReadPerMillionTokens = Math.max(0, cacheReadPerMillionTokens);
        }

        public double getCacheWritePerMillionTokens() {
            return cacheWritePerMillionTokens;
        }

        public void setCacheWritePerMillionTokens(double cacheWritePerMillionTokens) {
            this.cacheWritePerMillionTokens = Math.max(0, cacheWritePerMillionTokens);
        }
    }
}
