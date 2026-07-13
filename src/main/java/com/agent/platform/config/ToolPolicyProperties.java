package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "enterprise-agent.tool-policy")
public class ToolPolicyProperties {

    private String defaultDecision = "allow";
    private Map<String, String> decisions = new LinkedHashMap<>();
    private List<String> adminOnlyTools = new ArrayList<>();
    private List<String> allowedFilesystemRoots = new ArrayList<>();
    private List<String> allowedNetworkHosts = new ArrayList<>();
    private Map<String, List<String>> tenantDeniedTools = new LinkedHashMap<>();

    public String getDefaultDecision() {
        return defaultDecision;
    }

    public void setDefaultDecision(String defaultDecision) {
        this.defaultDecision = defaultDecision;
    }

    public Map<String, String> getDecisions() {
        return decisions;
    }

    public void setDecisions(Map<String, String> decisions) {
        this.decisions = decisions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(decisions);
    }

    public List<String> getAdminOnlyTools() {
        return adminOnlyTools;
    }

    public void setAdminOnlyTools(List<String> adminOnlyTools) {
        this.adminOnlyTools = adminOnlyTools == null ? new ArrayList<>() : new ArrayList<>(adminOnlyTools);
    }

    public List<String> getAllowedFilesystemRoots() {
        return allowedFilesystemRoots;
    }

    public void setAllowedFilesystemRoots(List<String> allowedFilesystemRoots) {
        this.allowedFilesystemRoots = allowedFilesystemRoots == null ? new ArrayList<>() : new ArrayList<>(allowedFilesystemRoots);
    }

    public List<String> getAllowedNetworkHosts() {
        return allowedNetworkHosts;
    }

    public void setAllowedNetworkHosts(List<String> allowedNetworkHosts) {
        this.allowedNetworkHosts = allowedNetworkHosts == null ? new ArrayList<>() : new ArrayList<>(allowedNetworkHosts);
    }

    public Map<String, List<String>> getTenantDeniedTools() {
        return tenantDeniedTools;
    }

    public void setTenantDeniedTools(Map<String, List<String>> tenantDeniedTools) {
        this.tenantDeniedTools = tenantDeniedTools == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tenantDeniedTools);
    }
}
