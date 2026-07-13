package com.agent.platform.runtime;

import com.agent.platform.memory.MemorySearchResult;
import com.agent.platform.memory.MemoryService;
import com.agent.platform.memory.UserProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从完整数据库时间线投影本轮模型视图。
 *
 * <p>原始消息永不删除。裁剪以消息单元为粒度，ToolCall 与对应 ToolResult 始终成对
 * 保留或成对省略，避免模型看到孤立工具结果。</p>
 */
@Service
public class DefaultAgentContextManager implements AgentContextManager {

    private static final int MAX_TIMELINE_MESSAGES = 2_000;

    private final AgentTimelineStore timelineStore;
    private final TokenEstimator tokenEstimator;

    private final MemoryService memoryService;

    public DefaultAgentContextManager(AgentTimelineStore timelineStore,
                                      TokenEstimator tokenEstimator,
                                      MemoryService memoryService) {
        this.timelineStore = timelineStore;
        this.tokenEstimator = tokenEstimator;
        this.memoryService = memoryService;
    }

    @Override
    public AgentContextView project(String sessionId, String userId, String query, long maxTokens) {
        List<AgentMessage> timeline = timelineStore.loadMessages(sessionId, MAX_TIMELINE_MESSAGES);
        if (timeline.isEmpty()) {
            return new AgentContextView(List.of(), 0, 0, false);
        }
        long budget = Math.max(1, maxTokens);
        List<MessageUnit> units = buildUnits(timeline);
        List<MessageUnit> selected = new ArrayList<>();
        long selectedTokens = 0;

        AgentMessage latestSummary = timeline.stream()
                .filter(message -> message.type() == AgentMessageType.CONTEXT_SUMMARY)
                .max(Comparator.comparingLong(AgentMessage::sequence))
                .orElse(null);
        if (latestSummary != null) {
            MessageUnit summaryUnit = new MessageUnit(List.of(latestSummary), tokens(latestSummary));
            selected.add(summaryUnit);
            selectedTokens += summaryUnit.tokens();
        }

        for (int index = units.size() - 1; index >= 0; index--) {
            MessageUnit unit = units.get(index);
            if (latestSummary != null && unit.messages().contains(latestSummary)) {
                continue;
            }
            if (selectedTokens + unit.tokens() > budget && !selected.isEmpty()) {
                continue;
            }
            selected.add(unit);
            selectedTokens += unit.tokens();
            if (selectedTokens >= budget) {
                break;
            }
        }

        List<AgentMessage> projected = new ArrayList<>(selected.stream()
                .flatMap(unit -> unit.messages().stream())
                .distinct()
                .sorted(Comparator.comparingLong(AgentMessage::sequence))
                .toList());
        AgentMessage memoryContext = longTermMemoryContext(sessionId, userId, query);
        if (memoryContext != null) {
            long memoryTokens = tokens(memoryContext);
            while (!projected.isEmpty() && selectedTokens + memoryTokens > budget) {
                AgentMessage removed = projected.remove(0);
                selectedTokens = Math.max(0, selectedTokens - tokens(removed));
                if (removed.isToolCall()) {
                    String toolCallId = removed.toolCallId();
                    AgentMessage paired = projected.stream()
                            .filter(message -> message.isToolResult() && toolCallId.equals(message.toolCallId()))
                            .findFirst()
                            .orElse(null);
                    if (paired != null) {
                        projected.remove(paired);
                        selectedTokens = Math.max(0, selectedTokens - tokens(paired));
                    }
                }
                else if (removed.isToolResult()) {
                    String toolCallId = removed.toolCallId();
                    AgentMessage paired = projected.stream()
                            .filter(message -> message.isToolCall() && toolCallId.equals(message.toolCallId()))
                            .findFirst()
                            .orElse(null);
                    if (paired != null) {
                        projected.remove(paired);
                        selectedTokens = Math.max(0, selectedTokens - tokens(paired));
                    }
                }
            }
            projected.add(0, memoryContext);
            selectedTokens += memoryTokens;
        }
        long projectedTimelineMessages = projected.stream()
                .filter(message -> !message.messageId().startsWith("memory-context-"))
                .count();
        int omitted = Math.max(0, timeline.size() - (int) projectedTimelineMessages);
        return new AgentContextView(List.copyOf(projected), selectedTokens, omitted, omitted > 0);
    }

    private List<MessageUnit> buildUnits(List<AgentMessage> messages) {
        Map<String, AgentMessage> toolResults = new HashMap<>();
        for (AgentMessage message : messages) {
            if (message.isToolResult()) {
                toolResults.put(message.toolCallId(), message);
            }
        }
        Set<String> groupedResults = new HashSet<>();
        List<MessageUnit> units = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message.isToolResult() && groupedResults.contains(message.toolCallId())) {
                continue;
            }
            if (message.isToolCall()) {
                AgentMessage result = toolResults.get(message.toolCallId());
                if (result != null) {
                    groupedResults.add(result.toolCallId());
                    List<AgentMessage> pair = List.of(message, result).stream()
                            .sorted(Comparator.comparingLong(AgentMessage::sequence))
                            .toList();
                    units.add(new MessageUnit(pair, tokens(message) + tokens(result)));
                    continue;
                }
            }
            units.add(new MessageUnit(List.of(message), tokens(message)));
        }
        return units;
    }

    private long tokens(AgentMessage message) {
        if (message.estimatedTokens() > 0) {
            return message.estimatedTokens();
        }
        return tokenEstimator.estimate(message.content())
                + tokenEstimator.estimate(String.valueOf(message.arguments()))
                + tokenEstimator.estimate(String.valueOf(message.metadata()));
    }

    private AgentMessage longTermMemoryContext(String sessionId, String userId, String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<MemorySearchResult> recalled = memoryService.recall(sessionId, userId, query, 8);
        UserProfile profile = memoryService.loadUserProfile(userId);
        if (recalled.isEmpty() && profile.items().isEmpty()) {
            return null;
        }
        StringBuilder content = new StringBuilder("<trusted_memory_context>\n");
        for (MemorySearchResult result : recalled) {
            content.append("- type=").append(result.type())
                    .append("; score=").append(result.score())
                    .append("; content=").append(result.content())
                    .append('\n');
        }
        for (var item : profile.items()) {
            content.append("- profile.").append(item.key()).append('=').append(item.value()).append('\n');
        }
        content.append("</trusted_memory_context>");
        String value = content.toString();
        return new AgentMessage(
                "memory-context-" + sessionId,
                sessionId,
                "",
                0,
                AgentMessageType.CONTEXT_SUMMARY,
                value,
                "",
                "",
                Map.of(),
                Map.of("source", "postgresql-long-term-memory", "recalled", recalled.size()),
                tokenEstimator.estimate(value),
                java.time.Instant.now()
        );
    }

    private record MessageUnit(List<AgentMessage> messages, long tokens) {
    }
}
