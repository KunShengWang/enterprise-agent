package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.workbench.routing")
public class WorkbenchRoutingProperties {

    private boolean enabled;
    private int maxAttempts = 2;
    private long staleAfterMillis = 15_000;
    private long leaseMillis = 15_000;
    private long retryBackoffMillis = 1_000;
    private int scanBatchSize = 20;
    private int maxIncidentRequestIds = 100;
    private long unknownResultTokenReserve = 8_192;
    private String catalogVersion = "workbench-v1";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { this.maxAttempts = Math.max(1, Math.min(2, value)); }
    public long getStaleAfterMillis() { return staleAfterMillis; }
    public void setStaleAfterMillis(long value) {
        this.staleAfterMillis = Math.max(1_000, value);
        this.leaseMillis = this.staleAfterMillis;
    }
    public long getLeaseMillis() { return leaseMillis; }
    public void setLeaseMillis(long value) { this.leaseMillis = Math.max(1_000, value); }
    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long value) { this.retryBackoffMillis = Math.max(0, value); }
    public int getScanBatchSize() { return scanBatchSize; }
    public void setScanBatchSize(int value) { this.scanBatchSize = Math.max(1, Math.min(100, value)); }
    public int getMaxIncidentRequestIds() { return maxIncidentRequestIds; }
    public void setMaxIncidentRequestIds(int value) { this.maxIncidentRequestIds = Math.max(1, Math.min(100, value)); }
    public long getUnknownResultTokenReserve() { return unknownResultTokenReserve; }
    public void setUnknownResultTokenReserve(long value) { this.unknownResultTokenReserve = Math.max(0, value); }
    public String getCatalogVersion() { return catalogVersion; }
    public void setCatalogVersion(String value) {
        this.catalogVersion = value == null || value.isBlank() ? "workbench-v1" : value.trim();
    }
}
