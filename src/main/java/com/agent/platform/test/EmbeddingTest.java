package com.agent.platform.test;

public class EmbeddingTest {

    public static void main(String[] args) {
        String embeddingApiKey = System.getenv("EMBEDDING_API_KEY");
        String deepseekApiKey = System.getenv("DEEPSEEK_API_KEY");
        System.out.println(embeddingApiKey);
        System.out.println(deepseekApiKey);
    }
}
