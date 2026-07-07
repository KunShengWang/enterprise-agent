package com.agent.platform.rag;

import com.agent.platform.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent.rag", name = "mode", havingValue = "pgvector", matchIfMissing = true)
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final RagProperties ragProperties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpenAiCompatibleEmbeddingClient(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public double[] embed(String text) {
        RagProperties.Embedding embedding = ragProperties.getEmbedding();
        if (embedding.getApiKey() == null || embedding.getApiKey().isBlank()) {
            throw new EmbeddingException("EMBEDDING_API_KEY is not configured");
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", embedding.getModel());
        requestBody.put("input", text == null ? "" : text);
        if (embedding.getDimension() > 0) {
            requestBody.put("dimensions", embedding.getDimension());
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint(embedding))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + embedding.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new EmbeddingException("Embedding API failed, status=" + response.statusCode() + ", body=" + safeBody(response.body()));
            }
            return parseEmbedding(response.body(), embedding.getDimension());
        }
        catch (IOException exception) {
            throw new EmbeddingException("Embedding API I/O failure", exception);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Embedding API call interrupted", exception);
        }
    }

    private URI endpoint(RagProperties.Embedding embedding) {
        String baseUrl = stripTrailingSlash(embedding.getBaseUrl());
        String path = embedding.getPath().startsWith("/") ? embedding.getPath() : "/" + embedding.getPath();
        return URI.create(baseUrl + path);
    }

    private double[] parseEmbedding(String body, int expectedDimension) {
        try {
            JsonNode embeddingNode = objectMapper.readTree(body).path("data").path(0).path("embedding");
            if (!embeddingNode.isArray()) {
                throw new EmbeddingException("Embedding API response does not contain data[0].embedding");
            }
            double[] vector = new double[embeddingNode.size()];
            for (int index = 0; index < embeddingNode.size(); index++) {
                vector[index] = embeddingNode.get(index).asDouble();
            }
            if (expectedDimension > 0 && vector.length != expectedDimension) {
                throw new EmbeddingException("Embedding dimension mismatch, expected=" + expectedDimension + ", actual=" + vector.length);
            }
            return vector;
        }
        catch (Exception exception) {
            throw new EmbeddingException("Failed to parse embedding response", exception);
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new EmbeddingException("Embedding base URL is not configured");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String safeBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }
}
