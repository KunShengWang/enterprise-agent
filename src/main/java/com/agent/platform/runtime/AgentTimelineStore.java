package com.agent.platform.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Session、结构化消息和 Runtime 事件的持久化边界。
 */
public interface AgentTimelineStore {

    AgentSession openSession(String sessionId, String userId);

    Optional<AgentSession> findSession(String sessionId);

    List<AgentMessage> appendMessages(String sessionId,
                                      String userId,
                                      String runId,
                                      List<AgentMessageDraft> messages);

    List<AgentMessage> loadMessages(String sessionId, int limit);

    AgentEvent appendEvent(String sessionId,
                           String userId,
                           String runId,
                           AgentEventDraft event);

    List<AgentEvent> loadEvents(String runId, int limit);
}
