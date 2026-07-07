package com.agent.platform.rag;

public record DocumentChunk(
        String chunkId,
        String source,// chunk 的来源
        int chunkIndex,// 当前 chunk 在整个文档 chunks 的位置
        String content// chunk 的内容
) {
}
