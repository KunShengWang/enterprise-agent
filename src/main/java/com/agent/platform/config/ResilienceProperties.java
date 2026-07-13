package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.resilience")
public class ResilienceProperties {

    private final RateLimit rateLimit = new RateLimit();

    private final Llm llm = new Llm();

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Llm getLlm() {
        return llm;
    }

    public static class RateLimit {

        private boolean enabled = true;

        private int maxRequestsPerMinute = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequestsPerMinute() {
            return maxRequestsPerMinute;
        }

        public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
            this.maxRequestsPerMinute = maxRequestsPerMinute;
        }
    }

    public static class Llm {

        private int maxAttempts = 3;

        private long timeoutMillis = 10000;

        private long backoffMillis = 300;

        private boolean fallbackEnabled = true;

        private String fallbackMessage = "AI 服务暂时不可用，请稍后重试。";

        private int executorThreads = 8;

        private int executorQueueCapacity = 64;

        private int circuitBreakerFailureThreshold = 5;

        private long circuitBreakerOpenMillis = 30000;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public long getBackoffMillis() {
            return backoffMillis;
        }

        public void setBackoffMillis(long backoffMillis) {
            this.backoffMillis = backoffMillis;
        }

        public boolean isFallbackEnabled() {
            return fallbackEnabled;
        }

        public void setFallbackEnabled(boolean fallbackEnabled) {
            this.fallbackEnabled = fallbackEnabled;
        }

        public String getFallbackMessage() {
            return fallbackMessage;
        }

        public void setFallbackMessage(String fallbackMessage) {
            this.fallbackMessage = fallbackMessage;
        }

        public int getExecutorThreads() {
            return executorThreads;
        }

        public void setExecutorThreads(int executorThreads) {
            this.executorThreads = executorThreads;
        }

        public int getExecutorQueueCapacity() {
            return executorQueueCapacity;
        }

        public void setExecutorQueueCapacity(int executorQueueCapacity) {
            this.executorQueueCapacity = executorQueueCapacity;
        }

        public int getCircuitBreakerFailureThreshold() {
            return circuitBreakerFailureThreshold;
        }

        public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
            this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
        }

        public long getCircuitBreakerOpenMillis() {
            return circuitBreakerOpenMillis;
        }

        public void setCircuitBreakerOpenMillis(long circuitBreakerOpenMillis) {
            this.circuitBreakerOpenMillis = circuitBreakerOpenMillis;
        }
    }
}
