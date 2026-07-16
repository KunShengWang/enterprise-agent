package com.agent.platform.runtime;

/**
 * Runtime 对外发布且先落库的统一事件类型。
 */
public enum AgentEventType {
    RUN_STARTED,// 运行已启动
    RUN_RESUMED,// 运行已恢复
    CONTEXT_PREPARED,// 上下文已准备
    CONTEXT_COMPACTED,// 上下文压缩
    MODEL_STARTED,// 模型已启动
    MODEL_DELTA,// 模型增量
    MODEL_COMPLETED,// 模型已完成
    MODEL_FAILED,// 模型失败
    TOOL_REQUESTED,// 工具请求
    POLICY_DECIDED,// 政策已决定
    APPROVAL_REQUIRED,// 需要批准
    TOOL_STARTED,// 工具已启动
    TOOL_COMPLETED,// 工具已完成
    SUB_AGENT_STARTED,// 子代理已启动
    SUB_AGENT_COMPLETED,// 子代理已完成
    RUN_COMPLETED,// 运行完成
    RUN_FAILED,// 运行失败
    RUN_CANCELLED,// 运行已取消
    HEARTBEAT// 心跳
}
