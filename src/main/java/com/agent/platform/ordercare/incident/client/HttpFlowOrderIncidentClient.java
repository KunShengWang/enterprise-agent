package com.agent.platform.ordercare.incident.client;

import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.model.IncidentDeadLetterFacts;
import com.agent.platform.ordercare.incident.model.IncidentFactEnvelope;
import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import com.agent.platform.ordercare.incident.model.IncidentInventoryFacts;
import com.agent.platform.ordercare.incident.model.IncidentOrderFacts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

@Component
public class HttpFlowOrderIncidentClient implements FlowOrderIncidentClient {

    private static final String ORDER_FACTS_PATH = "/internal/incidents/facts/orders";
    private static final String INVENTORY_FACTS_PATH = "/internal/incidents/facts/inventory";
    private static final String DEAD_LETTER_FACTS_PATH = "/internal/incidents/facts/dead-letters";

    private final OrderCareProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public HttpFlowOrderIncidentClient(OrderCareProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build());
    }

    HttpFlowOrderIncidentClient(OrderCareProperties properties,
                                ObjectMapper objectMapper,
                                HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public IncidentFactEnvelope<IncidentOrderFacts> queryOrderFacts(IncidentFactQuery query, String traceId) {
        return send(ORDER_FACTS_PATH, query, traceId, IncidentOrderFacts.class);
    }

    @Override
    public IncidentFactEnvelope<IncidentInventoryFacts> queryInventoryFacts(IncidentFactQuery query, String traceId) {
        return send(INVENTORY_FACTS_PATH, query, traceId, IncidentInventoryFacts.class);
    }

    @Override
    public IncidentFactEnvelope<IncidentDeadLetterFacts> queryDeadLetterFacts(IncidentFactQuery query,
                                                                              String traceId) {
        return send(DEAD_LETTER_FACTS_PATH, query, traceId, IncidentDeadLetterFacts.class);
    }

    private <T> IncidentFactEnvelope<T> send(String path,
                                             IncidentFactQuery query,
                                             String traceId,
                                             Class<T> factsType) {
        HttpRequest request = post(path, query, traceId);
        FlowOrderApiException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getInspectMaxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return decode(response.body(), factsType);
                }
                boolean retryable = response.statusCode() == 502 || response.statusCode() == 503;
                lastFailure = new FlowOrderApiException(
                        "FlowOrder incident fact query failed: HTTP " + response.statusCode(),
                        response.statusCode(),
                        retryable);
            } catch (IOException exception) {
                lastFailure = new FlowOrderApiException(
                        "FlowOrder incident fact query network failure",
                        0,
                        true,
                        exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new FlowOrderApiException(
                        "FlowOrder incident fact query interrupted",
                        0,
                        false,
                        exception);
            }
            if (lastFailure == null
                    || !lastFailure.retryable()
                    || attempt >= properties.getInspectMaxAttempts()) {
                break;
            }
            backoff(attempt);
        }
        throw lastFailure == null
                ? new FlowOrderApiException("FlowOrder incident fact query failed", 0, false)
                : lastFailure;
    }

    private HttpRequest post(String path, IncidentFactQuery query, String traceId) {
        String body;
        try {
            body = objectMapper.writeValueAsString(query);
        } catch (RuntimeException exception) {
            throw new FlowOrderApiException("serialize incident fact query failed", 0, false, exception);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        trimTrailingSlash(properties.getFloworderBaseUrl()) + path))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Trace-Id", traceId.trim());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private <T> IncidentFactEnvelope<T> decode(String body, Class<T> factsType) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(body, Map.class);
            int code = envelope.get("code") instanceof Number number ? number.intValue() : 0;
            if (code != 200) {
                throw new FlowOrderApiException(
                        "FlowOrder incident fact business error: " + envelope.get("message"),
                        code,
                        false);
            }
            if (!(envelope.get("data") instanceof Map<?, ?> rawData)) {
                throw new FlowOrderApiException("FlowOrder incident fact returned empty data", code, false);
            }
            Map<String, Object> data = (Map<String, Object>) rawData;
            T facts = objectMapper.convertValue(data.get("facts"), factsType);
            return new IncidentFactEnvelope<>(
                    string(data.get("schemaVersion")),
                    string(data.get("sourceSystem")),
                    string(data.get("sourceReference")),
                    string(data.get("scopeHash")),
                    OffsetDateTime.parse(string(data.get("observedAt"))),
                    Boolean.TRUE.equals(data.get("truncated")),
                    stringList(data.get("missingRequestIds")),
                    facts);
        } catch (FlowOrderApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FlowOrderApiException(
                    "FlowOrder incident fact response contract mismatch",
                    200,
                    false,
                    exception);
        }
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return List.copyOf(Arrays.asList(objectMapper.convertValue(value, String[].class)));
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void backoff(int attempt) {
        long millis = properties.getInspectRetryBackoffMillis() * attempt;
        if (millis > 0) {
            LockSupport.parkNanos(Duration.ofMillis(millis).toNanos());
        }
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
