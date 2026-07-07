package com.agent.platform.prompt;

import com.agent.platform.agent.AgentRequest;
import com.agent.platform.memory.ConversationMemory;
import com.agent.platform.memory.LongTermMemory;
import com.agent.platform.memory.MemorySearchResult;
import com.agent.platform.memory.UserProfileItem;
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
        if (memory != null && !memory.userProfile().items().isEmpty()) {
            contextBlocks.add("User profile: " + formatProfile(memory.userProfile().items()));
        }
        if (memory != null && !memory.longTermMemories().isEmpty()) {
            contextBlocks.add("Long-term memory: " + formatLongTermMemories(memory.longTermMemories()));
        }
        if (memory != null && !memory.recalledMemories().isEmpty()) {
            contextBlocks.add("Recalled memory: " + formatRecalledMemories(memory.recalledMemories()));
        }
        if (ragResult != null) {
            for (RetrievedDocument document : ragResult.documents()) {
                contextBlocks.add(formatRagBlock(document));
            }
        }
        if (toolResults != null) {
            for (ToolCallResult toolResult : toolResults) {
                contextBlocks.add("Tool[" + toolResult.toolName() + "] success=" + toolResult.success() + ": " + toolResult.content());
            }
        }

        String systemPrompt = """
                你是企业知识库和智能工单 Agent。
                你既可以进行自然、简洁的普通对话，也可以处理企业知识库问答和工单任务。
                对普通问候、闲聊、概念解释，可以自然回答。
                对涉及工单状态、审批结论、系统数据的业务问题，必须优先依据检索资料、工具结果和会话记忆回答。
                如果业务资料不足，要明确说明资料不足，不能编造工单状态、审批结论或系统数据。
                回答使用简洁中文；涉及知识库问答时，优先引用 RAG 资料中的 source 和 chunkIndex。
                涉及工单操作时，要说明依据来自工具结果。
                """.strip();
        String userPrompt = "用户问题：" + request.question();
        return new PromptRequest(systemPrompt, userPrompt, contextBlocks, Map.of("conversationId", request.conversationId()));
    }

    private String formatRagBlock(RetrievedDocument document) {
        Map<String, Object> metadata = document.metadata();
        Object source = metadata.getOrDefault("source", document.title());
        Object chunkIndex = metadata.getOrDefault("chunkIndex", "unknown");
        Object rank = metadata.getOrDefault("rank", "unknown");
        return "RAG[rank=" + rank
                + ", id=" + document.documentId()
                + ", source=" + source
                + ", chunkIndex=" + chunkIndex
                + ", score=" + document.score()
                + "] " + document.content();
    }

    private String formatProfile(List<UserProfileItem> items) {
        return items.stream()
                .map(item -> item.key() + "=" + item.value())
                .toList()
                .toString();
    }

    private String formatLongTermMemories(List<LongTermMemory> memories) {
        return memories.stream()
                .map(memory -> "[" + memory.category() + ", confidence=" + memory.confidence() + "] " + memory.content())
                .toList()
                .toString();
    }

    private String formatRecalledMemories(List<MemorySearchResult> memories) {
        return memories.stream()
                .map(memory -> "[" + memory.type() + ", score=" + memory.score() + "] " + memory.content())
                .toList()
                .toString();
    }
}
