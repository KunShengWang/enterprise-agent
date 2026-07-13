package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.memory")
public class MemoryProperties {

    private int longTermCandidateLimit = 40;
    private int profileItemLimit = 30;
    private double semanticWeight = 0.75;
    private double lexicalWeight = 0.10;
    private double minimumRecallScore = 0.30;
    private final Datasource datasource = new Datasource();

    public int getLongTermCandidateLimit() {
        return longTermCandidateLimit;
    }

    public void setLongTermCandidateLimit(int longTermCandidateLimit) {
        this.longTermCandidateLimit = longTermCandidateLimit;
    }

    public int getProfileItemLimit() {
        return profileItemLimit;
    }

    public void setProfileItemLimit(int profileItemLimit) {
        this.profileItemLimit = profileItemLimit;
    }

    public double getSemanticWeight() {
        return semanticWeight;
    }

    public void setSemanticWeight(double semanticWeight) {
        this.semanticWeight = semanticWeight;
    }

    public double getLexicalWeight() {
        return lexicalWeight;
    }

    public void setLexicalWeight(double lexicalWeight) {
        this.lexicalWeight = lexicalWeight;
    }

    public double getMinimumRecallScore() {
        return minimumRecallScore;
    }

    public void setMinimumRecallScore(double minimumRecallScore) {
        this.minimumRecallScore = minimumRecallScore;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public static class Datasource {
        private String url = "jdbc:postgresql://localhost:5432/enterprise_agent";
        private String username = "postgres";
        private String password = "";

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
