package com.agent.platform.rag;

public interface EmbeddingClient {

    double[] embed(String text);
}
