package com.agent.platform.ordercare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.ordercare")
public class OrderCareProperties {

    private String floworderBaseUrl = "http://localhost:8081";
    private long connectTimeoutMillis = 1_000;
    private long readTimeoutMillis = 3_000;
    private int inspectMaxAttempts = 2;
    private long inspectRetryBackoffMillis = 150;

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
}
