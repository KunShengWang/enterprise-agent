package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.memory")
public class MemoryProperties {

    private String mode = "jdbc";

    private int windowSize = 12;

    private int summaryTriggerMessages = 12;

    private int summaryMaxChars = 1200;

    private int recallLimit = 8;

    private int longTermLimit = 20;

    private int profileItemLimit = 30;

    private final Datasource datasource = new Datasource();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public int getSummaryTriggerMessages() {
        return summaryTriggerMessages;
    }

    public void setSummaryTriggerMessages(int summaryTriggerMessages) {
        this.summaryTriggerMessages = summaryTriggerMessages;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }

    public int getRecallLimit() {
        return recallLimit;
    }

    public void setRecallLimit(int recallLimit) {
        this.recallLimit = recallLimit;
    }

    public int getLongTermLimit() {
        return longTermLimit;
    }

    public void setLongTermLimit(int longTermLimit) {
        this.longTermLimit = longTermLimit;
    }

    public int getProfileItemLimit() {
        return profileItemLimit;
    }

    public void setProfileItemLimit(int profileItemLimit) {
        this.profileItemLimit = profileItemLimit;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public static class Datasource {

        private String url = "jdbc:postgresql://localhost:5432/enterprise_agent";

        private String username = "postgres";

        private String password = "postgres";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
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
    }
}
