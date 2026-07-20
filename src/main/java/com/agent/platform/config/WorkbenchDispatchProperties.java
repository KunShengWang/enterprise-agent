package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.workbench.dispatch")
public class WorkbenchDispatchProperties {
    private boolean enabled;
    private int maxAttempts = 2;
    private long staleAfterMillis = 15_000;
    private long retryBackoffMillis = 1_000;
    private long scanDelayMillis = 5_000;
    private int scanBatchSize = 20;
    private long previewTtlSeconds = 300;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = Math.max(1, Math.min(2, value)); }
    public long getStaleAfterMillis() { return staleAfterMillis; }
    public void setStaleAfterMillis(long value) { staleAfterMillis = Math.max(1_000, value); }
    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long value) { retryBackoffMillis = Math.max(0, value); }
    public long getScanDelayMillis() { return scanDelayMillis; }
    public void setScanDelayMillis(long value) { scanDelayMillis = Math.max(1_000, value); }
    public int getScanBatchSize() { return scanBatchSize; }
    public void setScanBatchSize(int value) { scanBatchSize = Math.max(1, Math.min(100, value)); }
    public long getPreviewTtlSeconds() { return previewTtlSeconds; }
    public void setPreviewTtlSeconds(long value) { previewTtlSeconds = Math.max(30, value); }
}
