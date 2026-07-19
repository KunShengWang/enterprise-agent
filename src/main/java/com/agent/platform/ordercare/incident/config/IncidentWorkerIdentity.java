package com.agent.platform.ordercare.incident.config;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

@Component
public class IncidentWorkerIdentity {

    private final String value;

    public IncidentWorkerIdentity(IncidentCommandProperties properties) {
        this.value = properties.getInstanceId().isBlank() ? generated() : properties.getInstanceId();
    }

    public String value() {
        return value;
    }

    private String generated() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + ManagementFactory.getRuntimeMXBean().getName()
                    + ":" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception ignored) {
            return "incident-worker:" + UUID.randomUUID();
        }
    }
}
