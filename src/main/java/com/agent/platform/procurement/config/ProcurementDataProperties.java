package com.agent.platform.procurement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

@ConfigurationProperties(prefix = "enterprise-agent.procurement")
public class ProcurementDataProperties {
    private String dataDir = "data/procurement/aws-synthetic";
    private String scenarioDir = "data/procurement/scenarios";
    private String scenarioFile = "complex_workstation_01.json";
    private String provider = "synthetic";
    private String mcpToolPrefix = "mcp.procurement.";

    public String getDataDir() { return dataDir; }
    public void setDataDir(String value) { dataDir = value == null || value.isBlank() ? dataDir : value.trim(); }
    public String getScenarioDir() { return scenarioDir; }
    public void setScenarioDir(String value) { scenarioDir = value == null || value.isBlank() ? scenarioDir : value.trim(); }
    public String getScenarioFile() { return scenarioFile; }
    public void setScenarioFile(String value) { scenarioFile = value == null ? "" : value.trim(); }
    public String getProvider() { return provider; }
    public void setProvider(String value) {
        provider = value == null || value.isBlank() ? provider : value.trim().toLowerCase(Locale.ROOT);
    }
    public String getMcpToolPrefix() { return mcpToolPrefix; }
    public void setMcpToolPrefix(String value) {
        mcpToolPrefix = value == null || value.isBlank() ? mcpToolPrefix : value.trim();
    }
}
