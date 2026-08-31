package com.agent.platform.procurement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enterprise-agent.procurement")
public class ProcurementDataProperties {
    private String dataDir = "data/procurement/aws-synthetic";
    private String scenarioDir = "data/procurement/scenarios";

    public String getDataDir() { return dataDir; }
    public void setDataDir(String value) { dataDir = value == null || value.isBlank() ? dataDir : value.trim(); }
    public String getScenarioDir() { return scenarioDir; }
    public void setScenarioDir(String value) { scenarioDir = value == null || value.isBlank() ? scenarioDir : value.trim(); }
}
