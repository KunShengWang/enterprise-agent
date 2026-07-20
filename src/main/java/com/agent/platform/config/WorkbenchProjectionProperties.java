package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.workbench.projection")
public class WorkbenchProjectionProperties {
    private boolean enabled;
    private int sourceBatchSize = 200;
    private int eventBatchSize = 500;
    private long leaseMillis = 15_000;
    private String instanceId = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSourceBatchSize() { return sourceBatchSize; }
    public void setSourceBatchSize(int value) { sourceBatchSize = Math.max(1, Math.min(1000, value)); }
    public int getEventBatchSize() { return eventBatchSize; }
    public void setEventBatchSize(int value) { eventBatchSize = Math.max(1, Math.min(2000, value)); }
    public long getLeaseMillis() { return leaseMillis; }
    public void setLeaseMillis(long value) { leaseMillis = Math.max(1_000, value); }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String value) { instanceId = value == null ? "" : value.trim(); }
}
