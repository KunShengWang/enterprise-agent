package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent")
public class AgentProperties {

    private boolean mockMode = false;

    private int maxModelCallsPerRun = 8;

    private int maxToolCallsPerRun = 6;

    private int maxToolExecutionAttempts = 2;

    private long toolRetryBackoffMillis = 150;

    private boolean replanAfterToolFailure = true;

    private int streamBackpressureBufferSize = 256;

    private int streamHeartbeatSeconds = 10;

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

    public void setMaxModelCallsPerRun(int maxModelCallsPerRun) {
        this.maxModelCallsPerRun = maxModelCallsPerRun;
    }

    public int getMaxToolCallsPerRun() {
        return maxToolCallsPerRun;
    }

    public void setMaxToolCallsPerRun(int maxToolCallsPerRun) {
        this.maxToolCallsPerRun = maxToolCallsPerRun;
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

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }
}
