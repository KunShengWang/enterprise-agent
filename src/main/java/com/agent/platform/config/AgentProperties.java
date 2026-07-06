package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent")
public class AgentProperties {

    private boolean mockMode = false;

    private int maxModelCallsPerRun = 8;

    private int maxToolCallsPerRun = 6;

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

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }
}
