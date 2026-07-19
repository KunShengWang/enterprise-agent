package com.agent.platform.ordercare.incident.model;

public record TaskLeaseClaim(
        AgentTaskRecord task,
        AgentTaskStatus previousStatus,
        boolean claimed,
        boolean takeover
) { }
