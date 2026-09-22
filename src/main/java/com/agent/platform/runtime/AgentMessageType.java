package com.agent.platform.runtime;

/**
 * Agent 会话时间线中的结构化消息类型。
 */
public enum AgentMessageType {

    SYSTEM,

    USER,

    ASSISTANT_TEXT,

    ASSISTANT_TOOL_CALL,

    TOOL_RESULT,

    CONTEXT_SUMMARY,

    /**
     * 当前业务事实的非持久化 canonical 投影；与会话压缩摘要语义分离。
     */
    CANONICAL_CONTEXT
}
