package com.agent.platform.runtime;

import org.springframework.stereotype.Component;
import java.util.*;

/** Validates persisted depth-one lineage; no second run store and no procurement fact cache. */
@Component
public final class AgentRunLinks {
    private final AgentRunStore runs;
    public AgentRunLinks(AgentRunStore runs) { this.runs = runs; }
    public Optional<AgentRunRecord> parent(String runId) {
        var child = runs.find(runId).orElse(null);
        if (child == null || child.request() == null) return Optional.empty();
        String id = Objects.toString(child.request().metadata().get(ChildRunLaunch.PARENT), "");
        if (id.isBlank()) return Optional.empty();
        var parent = runs.find(id).orElseThrow(() -> new IllegalStateException("domain parent missing"));
        if (parent.request() == null || parent.request().metadata().containsKey(ChildRunLaunch.PARENT)
                || !id.equals(child.request().metadata().get(ChildRunLaunch.ROOT))
                || !child.userId().equals(parent.userId())
                || !Objects.equals(child.request().metadata().get("tenantId"), parent.request().metadata().get("tenantId"))
                || !child.conversationId().equals(parent.conversationId() + ":domain:" + child.runId()))
            throw new IllegalStateException("invalid domain lineage");
        return Optional.of(parent);
    }
    public String businessSession(String runId, String fallback) {
        return parent(runId).map(AgentRunRecord::conversationId).orElse(fallback);
    }
    public boolean permits(String runId, String tool) {
        var run = runs.find(runId).orElse(null);
        if (run == null || run.executionProfile() == null) return true;
        String name = run.executionProfile().name();
        boolean domain = name.equals(com.agent.platform.procurement.agent.AgentRegistry.SUPERVISOR)
                || Set.of(com.agent.platform.procurement.agent.AgentRegistry.REQUISITION,
                com.agent.platform.procurement.agent.AgentRegistry.SOURCING, com.agent.platform.procurement.agent.AgentRegistry.RFQ).contains(name);
        return !domain || run.executionProfile().allows(tool);
    }
    public boolean sameRoot(String left, String right) {
        var a = parent(left); var b = parent(right);
        return a.isPresent() && b.isPresent() && a.get().runId().equals(b.get().runId());
    }
}
