package com.agent.platform.workbench.dispatch;

import com.agent.platform.workbench.target.ExecutionTargetId;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ExecutionAdapterRegistry {
    private final Map<ExecutionTargetId, ExecutionAdapter> adapters;

    public ExecutionAdapterRegistry(List<ExecutionAdapter> adapters) {
        EnumMap<ExecutionTargetId, ExecutionAdapter> indexed = new EnumMap<>(ExecutionTargetId.class);
        for (ExecutionAdapter adapter : adapters) {
            if (indexed.put(adapter.targetId(), adapter) != null) {
                throw new IllegalStateException("duplicate ExecutionAdapter: " + adapter.targetId());
            }
        }
        if (indexed.size() != ExecutionTargetId.values().length) {
            throw new IllegalStateException("all ExecutionAdapters must be registered; found "
                    + indexed.keySet());
        }
        this.adapters = Map.copyOf(indexed);
    }

    public ExecutionAdapter require(String targetId) {
        ExecutionTargetId id = ExecutionTargetId.valueOf(targetId);
        ExecutionAdapter adapter = adapters.get(id);
        if (adapter == null) throw new IllegalStateException("ExecutionAdapter is not registered: " + id);
        return adapter;
    }

    public int size() { return adapters.size(); }
}
