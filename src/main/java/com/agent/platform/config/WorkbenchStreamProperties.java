package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.workbench.stream")
public class WorkbenchStreamProperties {
    private long pollIntervalMillis = 500;
    private int batchSize = 500;
    private int heartbeatEveryPolls = 20;

    public long getPollIntervalMillis() { return pollIntervalMillis; }
    public void setPollIntervalMillis(long value) { pollIntervalMillis = Math.max(100, Math.min(5000, value)); }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = Math.max(1, Math.min(2000, value)); }
    public int getHeartbeatEveryPolls() { return heartbeatEveryPolls; }
    public void setHeartbeatEveryPolls(int value) { heartbeatEveryPolls = Math.max(1, Math.min(120, value)); }
}
