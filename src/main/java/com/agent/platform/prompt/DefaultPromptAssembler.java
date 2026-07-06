package com.agent.platform.prompt;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.rag.RagResult;
import com.agent.platform.rag.RetrievedDocument;
import com.agent.platform.tool.ToolCallResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DefaultPromptAssembler implements PromptAssembler {

    @Override
    public PromptRequest assemble(AgentRequest request,
                                  ConversationMemory memory,
                                  RagResult ragResult,
                                  List<ToolCallResult> toolResults) {
        List<String> contextBlocks = new ArrayList<>();
        if (memory != null && memory.summary() != null && !memory.summary().isBlank()) {
            contextBlocks.add("Memory summary: " + memory.summary());
        }
        if (ragResult != null) {
            for (RetrievedDocument document : ragResult.documents()) {
                contextBlocks.add("RAG[" + document.documentId() + "] " + document.title() + ": " + document.content());
            }
        }
        if (toolResults != null) {
            for (ToolCallResult toolResult : toolResults) {
                contextBlocks.add("Tool[" + toolResult.toolName() + "] success=" + toolResult.success() + ": " + toolResult.content());
            }
        }

        String systemPrompt = "你是企业知识库和智能工单 Agent。必须优先依据资料和工具结果回答；资料不足时说明不足。";
        String userPrompt = "用户问题：" + request.question();
        return new PromptRequest(systemPrompt, userPrompt, contextBlocks, Map.of("conversationId", request.conversationId()));
    }
}
