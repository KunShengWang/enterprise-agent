package com.agent.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "enterprise-agent.workbench.web")
public class WorkbenchWebProperties {
    private boolean enabled;
    private String localTenantId = "local-tenant";
    private String localPrincipalId = "local-user";
    private Set<String> localRoles = new LinkedHashSet<>(Set.of("USER", "INCIDENT_OPERATOR"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLocalTenantId() { return localTenantId; }
    public void setLocalTenantId(String value) { localTenantId = normalize(value, "local-tenant"); }
    public String getLocalPrincipalId() { return localPrincipalId; }
    public void setLocalPrincipalId(String value) { localPrincipalId = normalize(value, "local-user"); }
    public Set<String> getLocalRoles() { return Set.copyOf(localRoles); }
    public void setLocalRoles(Set<String> value) {
        localRoles = value == null || value.isEmpty()
                ? new LinkedHashSet<>(Set.of("USER")) : new LinkedHashSet<>(value);
    }
    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
