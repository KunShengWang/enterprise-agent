package com.agent.platform.rag;

public interface RagService {

    RagResult retrieve(String query, int topK);
}
