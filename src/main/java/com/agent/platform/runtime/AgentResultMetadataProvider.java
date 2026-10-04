package com.agent.platform.runtime;

import java.util.Map;

/** Server-owned final facts, separate from the model's user-visible answer. */
public interface AgentResultMetadataProvider {
    Map<String,Object> provide(String runId);
}
