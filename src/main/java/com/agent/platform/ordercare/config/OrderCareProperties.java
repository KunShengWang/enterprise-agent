package com.agent.platform.ordercare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.ordercare")
public class OrderCareProperties {

    private String floworderBaseUrl = "http://localhost:8081";
    private long connectTimeoutMillis = 1_000;
    private long readTimeoutMillis = 3_000;
    private int inspectMaxAttempts = 2;
    private long inspectRetryBackoffMillis = 150;
    private int convergenceMaxAttempts = 8;
    private long convergenceIntervalMillis = 500;
    private int reconciliationMaxAttempts = 8;
    private long reconciliationIntervalMillis = 500;

    public String getFloworderBaseUrl() {
        return floworderBaseUrl;
    }

    public void setFloworderBaseUrl(String floworderBaseUrl) {
        this.floworderBaseUrl = floworderBaseUrl;
    }

    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = Math.max(100, connectTimeoutMillis);
    }

    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(long readTimeoutMillis) {
        this.readTimeoutMillis = Math.max(100, readTimeoutMillis);
    }

    public int getInspectMaxAttempts() {
        return inspectMaxAttempts;
    }

    public void setInspectMaxAttempts(int inspectMaxAttempts) {
        this.inspectMaxAttempts = Math.max(1, Math.min(inspectMaxAttempts, 3));
    }

    public long getInspectRetryBackoffMillis() {
        return inspectRetryBackoffMillis;
    }

    public void setInspectRetryBackoffMillis(long inspectRetryBackoffMillis) {
        this.inspectRetryBackoffMillis = Math.max(0, inspectRetryBackoffMillis);
    }

    public int getConvergenceMaxAttempts() {
        return convergenceMaxAttempts;
    }

    public void setConvergenceMaxAttempts(int convergenceMaxAttempts) {
        this.convergenceMaxAttempts = Math.max(1, Math.min(convergenceMaxAttempts, 30));
    }

    public long getConvergenceIntervalMillis() {
        return convergenceIntervalMillis;
    }

    public void setConvergenceIntervalMillis(long convergenceIntervalMillis) {
        this.convergenceIntervalMillis = Math.max(0, Math.min(convergenceIntervalMillis, 5_000));
    }

    public int getReconciliationMaxAttempts() {
        return reconciliationMaxAttempts;
    }

    public void setReconciliationMaxAttempts(int reconciliationMaxAttempts) {
        this.reconciliationMaxAttempts = Math.max(1, Math.min(reconciliationMaxAttempts, 30));
    }

    public long getReconciliationIntervalMillis() {
        return reconciliationIntervalMillis;
    }

    public void setReconciliationIntervalMillis(long reconciliationIntervalMillis) {
        this.reconciliationIntervalMillis = Math.max(0, Math.min(reconciliationIntervalMillis, 5_000));
    }
}
