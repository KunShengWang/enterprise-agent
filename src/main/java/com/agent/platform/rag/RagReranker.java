package com.agent.platform.rag;

import java.util.List;

public interface RagReranker {

    List<RetrievedDocument> rerank(String query, List<RetrievedDocument> candidates, int topK);
}
