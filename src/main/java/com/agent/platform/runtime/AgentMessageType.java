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

    CONTEXT_SUMMARY
}
