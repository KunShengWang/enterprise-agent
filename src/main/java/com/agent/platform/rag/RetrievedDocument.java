package com.agent.platform.rag;

import java.util.Map;

public record RetrievedDocument(
        String documentId,// 每个文档片段的唯一 ID，基于内容 hash 生成
        String title,
        String content,
        double score,// 相似度
        Map<String, Object> metadata
) {

    public RetrievedDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
