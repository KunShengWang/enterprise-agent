package com.agent.platform.rag;

public interface KnowledgeIngestionService {

    /**
     * 加载文档，向量化等并存入 postgresql
     */
    IngestionReport ingestConfiguredDirectory();
}
