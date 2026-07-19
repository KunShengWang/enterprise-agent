package com.agent.platform.ordercare.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "enterprise-agent.ordercare.incident-command")
public class IncidentCommandProperties {

    private boolean enabled;
    private int maxRequestIds = 100;
    private int deadlineSeconds = 120;
    private int maxParallelSpecialists = 3;
    private boolean recoveryPlannerEnabled;
    private int maxRecoveryPlanItems = 5;
    private boolean phase3Enabled;
    private boolean executionKillSwitch;
    private String instanceId = "";
    private int taskLeaseSeconds = 30;
    private int recoveryLeaseSeconds = 30;
    private int leaseHeartbeatSeconds = 10;
    private long staleScanIntervalMillis = 5000;
    private int staleScanBatchSize = 20;
    private String tenantScope = "local-demo-tenant";
    private Set<String> allowedQueues = new LinkedHashSet<>(Set.of(
            "floworder.order.create.dlq",
            "floworder.order.result.dlq",
            "floworder.order.state.dlq",
            "floworder.incident.e2e.dlq"
    ));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxRequestIds() { return maxRequestIds; }
    public void setMaxRequestIds(int maxRequestIds) { this.maxRequestIds = Math.max(1, Math.min(100, maxRequestIds)); }
    public int getDeadlineSeconds() { return deadlineSeconds; }
    public void setDeadlineSeconds(int deadlineSeconds) { this.deadlineSeconds = Math.max(30, deadlineSeconds); }
    public int getMaxParallelSpecialists() { return maxParallelSpecialists; }
    public void setMaxParallelSpecialists(int maxParallelSpecialists) {
        this.maxParallelSpecialists = Math.max(1, Math.min(3, maxParallelSpecialists));
    }
    public boolean isRecoveryPlannerEnabled() { return recoveryPlannerEnabled; }
    public void setRecoveryPlannerEnabled(boolean recoveryPlannerEnabled) {
        this.recoveryPlannerEnabled = recoveryPlannerEnabled;
    }
    public int getMaxRecoveryPlanItems() { return maxRecoveryPlanItems; }
    public void setMaxRecoveryPlanItems(int maxRecoveryPlanItems) {
        this.maxRecoveryPlanItems = Math.max(1, Math.min(10, maxRecoveryPlanItems));
    }
    public boolean isPhase3Enabled() { return phase3Enabled; }
    public void setPhase3Enabled(boolean phase3Enabled) { this.phase3Enabled = phase3Enabled; }
    public boolean isExecutionKillSwitch() { return executionKillSwitch; }
    public void setExecutionKillSwitch(boolean executionKillSwitch) { this.executionKillSwitch = executionKillSwitch; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId == null ? "" : instanceId.trim(); }
    public int getTaskLeaseSeconds() { return taskLeaseSeconds; }
    public void setTaskLeaseSeconds(int value) { this.taskLeaseSeconds = Math.max(5, Math.min(300, value)); }
    public int getRecoveryLeaseSeconds() { return recoveryLeaseSeconds; }
    public void setRecoveryLeaseSeconds(int value) { this.recoveryLeaseSeconds = Math.max(5, Math.min(300, value)); }
    public int getLeaseHeartbeatSeconds() { return leaseHeartbeatSeconds; }
    public void setLeaseHeartbeatSeconds(int value) { this.leaseHeartbeatSeconds = Math.max(1, Math.min(60, value)); }
    public long getStaleScanIntervalMillis() { return staleScanIntervalMillis; }
    public void setStaleScanIntervalMillis(long value) { this.staleScanIntervalMillis = Math.max(1000, value); }
    public int getStaleScanBatchSize() { return staleScanBatchSize; }
    public void setStaleScanBatchSize(int value) { this.staleScanBatchSize = Math.max(1, Math.min(100, value)); }
    public String getTenantScope() { return tenantScope; }
    public void setTenantScope(String tenantScope) {
        this.tenantScope = tenantScope == null || tenantScope.isBlank() ? "local-demo-tenant" : tenantScope.trim();
    }
    public Set<String> getAllowedQueues() { return Set.copyOf(allowedQueues); }
    public void setAllowedQueues(Set<String> allowedQueues) {
        this.allowedQueues = allowedQueues == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedQueues);
    }
}
