package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.storage")
public class AgentStorageProperties {

    private String mode = "jdbc";

    private final Datasource datasource = new Datasource();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public static class Datasource {

        private String url = "jdbc:postgresql://localhost:5432/enterprise_agent";

        private String username = "postgres";

        private String password = "1234";

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
