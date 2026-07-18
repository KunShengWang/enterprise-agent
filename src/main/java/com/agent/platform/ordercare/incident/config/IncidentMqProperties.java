package com.agent.platform.ordercare.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.ordercare.incident.rabbitmq-management")
public class IncidentMqProperties {

    private String baseUrl = "http://localhost:15672/api";
    private String virtualHost = "/";
    private String username = "guest";
    private String password = "guest";
    private long connectTimeoutMillis = 1_000;
    private long readTimeoutMillis = 1_500;
    private int maxAttempts = 2;
    private long retryBackoffMillis = 100;
    private int backlogThreshold = 50;
    private int unacknowledgedThreshold = 20;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public void setVirtualHost(String virtualHost) {
        this.virtualHost = virtualHost;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 2));
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = Math.max(0, Math.min(retryBackoffMillis, 1_000));
    }

    public int getBacklogThreshold() {
        return backlogThreshold;
    }

    public void setBacklogThreshold(int backlogThreshold) {
        this.backlogThreshold = Math.max(0, backlogThreshold);
    }

    public int getUnacknowledgedThreshold() {
        return unacknowledgedThreshold;
    }

    public void setUnacknowledgedThreshold(int unacknowledgedThreshold) {
        this.unacknowledgedThreshold = Math.max(0, unacknowledgedThreshold);
    }
}
