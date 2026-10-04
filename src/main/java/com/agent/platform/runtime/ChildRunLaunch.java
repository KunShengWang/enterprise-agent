package com.agent.platform.runtime;

import com.agent.platform.agent.AgentRequest;
import java.util.*;
import java.util.function.Supplier;

/** Server-only launch capability, never read from model arguments or public request metadata. */
public final class ChildRunLaunch {
    private ChildRunLaunch() {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    public static final String PARENT = "_domainParentRunId", ROOT = "_domainRootRunId";
    public static final String CALL = "_domainParentToolCallId", FINALIZE = "_domainFinalizeRef";
    public record Scope(String runId, String parentRunId, String toolCallId, String finalizeRef) {}
    public static Scope current() { return CURRENT.get(); }
    public static <T> T call(Scope scope, Supplier<T> operation) {
        if (CURRENT.get() != null) throw new IllegalStateException("recursive domain delegation forbidden");
        CURRENT.set(scope);
        try { return operation.get(); } finally { CURRENT.remove(); }
    }
    public static AgentRequest bind(AgentRequest request) {
        var metadata = new LinkedHashMap<>(request.metadata());
        metadata.keySet().removeIf(k -> k.startsWith("_domain"));
        Scope scope = current();
        if (scope != null) {
            metadata.put(PARENT, scope.parentRunId()); metadata.put(ROOT, scope.parentRunId());
            metadata.put(CALL, scope.toolCallId()); metadata.put(FINALIZE, scope.finalizeRef());
        }
        return new AgentRequest(request.conversationId(), request.userId(), request.question(), metadata, request.scenarioId());
    }
}
