package com.agent.platform.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Session、结构化消息和 Runtime 事件的持久化边界。
 */
public interface AgentTimelineStore {

    /**
     * 开启聊天窗口，并确认下一个消息和事件的序号
     */
    AgentSession openSession(String sessionId, String userId);

    Optional<AgentSession> findSession(String sessionId);

    /**
     * 往数据库中插入消息
     */
    List<AgentMessage> appendMessages(String sessionId,
                                      String userId,
                                      String runId,
                                      List<AgentMessageDraft> messages);

    /**
     * 从数据库加载有限的历史消息
     */
    List<AgentMessage> loadMessages(String sessionId, int limit);

    /**
     * 往数据库中添加 agent 事件
     */
    AgentEvent appendEvent(String sessionId,
                           String userId,
                           String runId,
                           AgentEventDraft event);

    List<AgentEvent> loadEvents(String runId, int limit);

    List<AgentEvent> loadEventsAfter(String runId, long afterSequence, int limit);
}
