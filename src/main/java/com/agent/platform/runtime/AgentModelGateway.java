package com.agent.platform.runtime;

public interface AgentModelGateway {

    AgentModelTurn nextTurn(AgentModelRequest request);

    /**
     * 执行一轮模型决策，并在模型生成最终回答时增量回调文本。
     *
     * <p>默认实现保持现有 Gateway 和测试替身兼容；支持 Provider 流式响应的实现应覆盖此方法。
     * ToolCall 的结构化 JSON 不得通过 delta 回调泄露给客户端。</p>
     */
    default AgentModelTurn nextTurn(AgentModelRequest request, AgentModelDeltaListener deltaListener) {
        return nextTurn(request);
    }
}
