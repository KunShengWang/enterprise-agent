package com.agent.platform.runtime;

import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Builds an ephemeral working context. It cannot write Case, timeline, approval, or Memory. */
public final class ContextAssembler {
    private final TokenEstimator estimator;
    private final ToolExecutionStore executions;
    private final ToolResultProjector projector;
    private final ObjectMapper json = new ObjectMapper();

    public ContextAssembler(TokenEstimator estimator, ToolExecutionStore executions, ToolResultProjector projector) {
        this.estimator = estimator; this.executions = executions; this.projector = projector;
    }

    public AgentContextView assemble(AgentContextView source, AgentExecutionProfile profile,
                                     AgentRunRecord run, List<ToolDefinition> tools, long messageBudget) {
        ContextPolicy policy = profile.contextPolicy();
        if (!policy.enabled()) return source;
        List<AgentMessage> messages = new ArrayList<>(source.messages());
        long before = messages.stream().mapToLong(this::tokens).sum();
        long rawBytes = 0, projectedBytes = 0;
        long rawToolTokens = 0, projectedToolTokens = 0;
        Map<String, String> duplicateKeys = new HashMap<>();
        Set<String> readOnlyTools = new HashSet<>();
        tools.stream().filter(t -> Boolean.TRUE.equals(t.metadata().get("readOnly"))
                && !Boolean.TRUE.equals(t.metadata().get("sideEffect"))).forEach(t -> readOnlyTools.add(t.name()));
        Map<String, String> callArguments = new HashMap<>();
        messages.stream().filter(AgentMessage::isToolCall).forEach(m -> callArguments.put(m.toolCallId(), encode(m.arguments())));
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            if (!message.isToolResult()) continue;
            // Never use another Run's execution record. Unknown/nonpersisted results retain the original envelope.
            ToolExecutionRecord execution = executions.findToolExecution(message.toolCallId()).orElse(null);
            if (execution == null || execution.result() == null || !Objects.equals(execution.runId(), message.runId())
                    || !Objects.equals(execution.toolName(), message.toolName())
                    || (execution.state() != ToolExecutionState.SUCCEEDED && execution.state() != ToolExecutionState.FAILED)
                    || Boolean.TRUE.equals(message.metadata().get("manualReview"))
                    || execution.result().success() != Boolean.TRUE.equals(message.metadata().get("success"))) continue;
            ToolCallResult raw = execution.result();
            ToolCallResult projected = projector.projectForContext(message.toolCallId(), raw);
            Map<String, Object> metadata = new LinkedHashMap<>(projected.metadata());
            metadata.put("success", projected.success()); metadata.put("error", projected.errorMessage() == null ? "" : projected.errorMessage());
            AgentMessage transformed = copy(message, projected.content(), metadata);
            messages.set(i, transformed);
            rawBytes += raw.content().getBytes(StandardCharsets.UTF_8).length;
            projectedBytes += projected.content().getBytes(StandardCharsets.UTF_8).length;
            rawToolTokens += estimator.estimate(raw.content()); projectedToolTokens += estimator.estimate(projected.content());
            if (raw.success() && execution.state() == ToolExecutionState.SUCCEEDED && readOnlyTools.contains(message.toolName())) {
                // Same facts, versions, evidence and arguments; observation timestamps alone may differ.
                duplicateKeys.put(message.toolCallId(), message.runId() + "|" + message.toolName() + "|"
                        + canonical(callArguments.getOrDefault(message.toolCallId(), "{}"), false) + "|" + canonical(projected.content(), true));
            }
        }
        List<AgentMessage> users = messages.stream().filter(m -> m.type() == AgentMessageType.USER).toList();
        long keepFrom = users.isEmpty() ? Long.MIN_VALUE : users.get(Math.max(0, users.size()-policy.recentUserTurns())).sequence();
        int removedConversation = 0;
        if (before > messageBudget * policy.compactionThreshold()) {
            int size = messages.size();
            messages.removeIf(m -> (m.type() == AgentMessageType.USER || m.type() == AgentMessageType.ASSISTANT_TEXT)
                    && m.sequence() < keepFrom && (run == null || !Objects.equals(m.runId(),run.runId())));
            removedConversation = size - messages.size();
        }
        Set<String> seen = new HashSet<>(), duplicateIds = new HashSet<>();
        for (int i = messages.size()-1; i >= 0; i--) {
            AgentMessage m = messages.get(i);
            String key = duplicateKeys.get(m.toolCallId());
            if (m.isToolResult() && key != null && !seen.add(key)) duplicateIds.add(m.toolCallId());
        }
        // Remove whole matched pairs, not lone tool responses. Raw timeline/execution evidence remains intact.
        messages.removeIf(m -> (m.isToolCall() || m.isToolResult()) && duplicateIds.contains(m.toolCallId()));
        Set<String> calls = new HashSet<>(), results = new HashSet<>();
        messages.stream().filter(AgentMessage::isToolCall).forEach(m -> calls.add(m.toolCallId()));
        messages.stream().filter(AgentMessage::isToolResult).forEach(m -> results.add(m.toolCallId()));
        messages.removeIf(m -> (m.isToolCall() && !results.contains(m.toolCallId())) || (m.isToolResult() && !calls.contains(m.toolCallId())));
        if (run != null) {
            Map<String, Object> state = new LinkedHashMap<>(run.runtimeState());
            state.put("remainingExecutionMillis", run.budgetSnapshot() == null ? 0 :
                    Math.max(0, java.time.Duration.between(Instant.now(), run.budgetSnapshot().deadline()).toMillis()));
            messages.add(0, new AgentMessage("working-run-state", run.conversationId(), run.runId(), 0,
                    AgentMessageType.CANONICAL_CONTEXT, encode(state), "", "", Map.of(),
                    Map.of("contextKind", "run_state", "trustedInstructions", false), 0, Instant.now()));
        }
        Map<String, Long> composition = new LinkedHashMap<>();
        composition.put("instructions", estimator.estimate(profile.systemPrompt()));
        composition.put("toolSchema", tools.stream().mapToLong(t -> estimator.estimate(t.name())+estimator.estimate(t.description())+estimator.estimate(t.inputSchema())).sum());
        for (String section : List.of("businessState", "runState", "conversation", "toolResult", "other")) composition.put(section,0L);
        for (AgentMessage m : messages) {
            String section = switch(m.type()) {
                case CANONICAL_CONTEXT -> "run_state".equals(m.metadata().get("contextKind")) ? "runState" : "businessState";
                case TOOL_RESULT -> "toolResult";
                case ASSISTANT_TOOL_CALL -> "other";
                case SYSTEM -> "instructions";
                default -> "conversation";
            };
            composition.merge(section,tokens(m),Long::sum);
        }
        long after = messages.stream().mapToLong(this::tokens).sum();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("contextEngineering", true); metrics.put("tokenAccounting", "estimated; ConservativeTokenEstimator; excludes provider framing");
        metrics.put("estimatedComposition", composition); metrics.put("estimatedWorkingContextTokens",composition.values().stream().mapToLong(Long::longValue).sum());
        metrics.put("contextTokensBefore", before); metrics.put("contextTokensAfter", after);
        metrics.put("conversationMessagesRemoved", removedConversation); metrics.put("duplicateToolPairsRemoved", duplicateIds.size());
        metrics.put("rawToolResultBytes", rawBytes); metrics.put("projectedToolResultBytes", projectedBytes);
        metrics.put("rawToolResultEstimatedTokens", rawToolTokens); metrics.put("projectedToolResultEstimatedTokens", projectedToolTokens);
        boolean compacted = removedConversation > 0 || !duplicateIds.isEmpty() || projectedBytes < rawBytes;
        metrics.put("compactionPerformed", compacted); metrics.put("budgetExceeded", after > messageBudget);
        // Mandatory business facts/current tool evidence are never silently dropped to manufacture budget compliance.
        return new AgentContextView(messages, after, 0, compacted, metrics);
    }

    private AgentMessage copy(AgentMessage m, String content, Map<String,Object> metadata) {
        return new AgentMessage(m.messageId(),m.sessionId(),m.runId(),m.sequence(),m.type(),content,
                m.toolCallId(),m.toolName(),m.arguments(),metadata,0,m.createdAt());
    }
    private long tokens(AgentMessage m) {
        if (m.isToolResult()) return estimator.estimate(encode(Map.of("content",m.content(),"metadata",m.metadata(),
                "success",Boolean.TRUE.equals(m.metadata().get("success")),"error",m.metadata().getOrDefault("error",""))));
        return estimator.estimate(m.content()) + (m.isToolCall() ? estimator.estimate(encode(m.arguments()))+estimator.estimate(m.toolName()) : 0);
    }
    private String canonical(String content, boolean observationTimes) {
        try { return encode(sorted(json.readValue(content,Object.class), observationTimes, false)); } catch(RuntimeException ignored) { return content; }
    }
    private Object sorted(Object value, boolean observationTimes, boolean source) {
        if (value instanceof Map<?,?> map) { Map<String,Object> out=new TreeMap<>();map.forEach((k,v)->{
            if (!(observationTimes && source && Set.of("retrievedAt", "fetchedAt", "collectedAt").contains(String.valueOf(k))))
                out.put(String.valueOf(k),sorted(v, observationTimes, "source".equals(String.valueOf(k))));
        });return out; }
        if (value instanceof List<?> list) return list.stream().map(v -> sorted(v, observationTimes, false)).toList();
        return value;
    }
    private String encode(Object value) { return json.writeValueAsString(value); }
}
