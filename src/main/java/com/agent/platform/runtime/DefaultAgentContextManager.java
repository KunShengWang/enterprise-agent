package com.agent.platform.runtime;

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

    public DefaultAgentContextManager(AgentTimelineStore timelineStore, TokenEstimator tokenEstimator) {
        this.timelineStore = timelineStore;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public AgentContextView project(String sessionId, long maxTokens) {
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

        List<AgentMessage> projected = selected.stream()
                .flatMap(unit -> unit.messages().stream())
                .distinct()
                .sorted(Comparator.comparingLong(AgentMessage::sequence))
                .toList();
        int omitted = Math.max(0, timeline.size() - projected.size());
        return new AgentContextView(projected, selectedTokens, omitted, omitted > 0);
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

    private record MessageUnit(List<AgentMessage> messages, long tokens) {
    }
}
