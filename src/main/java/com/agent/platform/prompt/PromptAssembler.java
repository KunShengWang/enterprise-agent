package com.agent.platform.prompt;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.rag.RagResult;
import com.agent.platform.tool.ToolCallResult;

import java.util.List;

public interface PromptAssembler {

    /**
     * 把 System Prompt、用户问题、Memory、RAG 证据和工具结果统一组装
     */
    PromptRequest assemble(AgentRequest request,
                           ConversationMemory memory,
                           RagResult ragResult,
                           List<ToolCallResult> toolResults);
}
