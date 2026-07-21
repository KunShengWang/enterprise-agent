package com.agent.platform.ordercare.incident.scope.client;

import com.agent.platform.ordercare.client.FlowOrderApiException;
import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HttpFlowOrderScopeDiscoveryClient implements FlowOrderScopeDiscoveryClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-FlowOrder-Internal-Token";
    private static final String ORDER_PATH = "/internal/incidents/scopes/order-candidates";
    private static final String RESOURCE_PATH = "/internal/incidents/scopes/resource-enrichment";

    private final OrderCareProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public HttpFlowOrderScopeDiscoveryClient(OrderCareProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis())).build());
    }

    HttpFlowOrderScopeDiscoveryClient(OrderCareProperties properties,
                                      ObjectMapper objectMapper,
                                      HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public FlowOrderOrderCandidates discoverOrders(String discoveryRequestId,
                                                    IncidentScopeCriteria criteria,
                                                    int limit,
                                                    String cursor,
                                                    String traceId) {
        ZoneId flowOrderZone = ZoneId.of(properties.getIncidentScopeDefaultTimezone());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("discoveryRequestId", discoveryRequestId);
        body.put("startTime", criteria.startTime() == null ? null
                : LocalDateTime.ofInstant(criteria.startTime(), flowOrderZone));
        body.put("endTime", criteria.endTime() == null ? null
                : LocalDateTime.ofInstant(criteria.endTime(), flowOrderZone));
        body.put("anomalyTypes", criteria.anomalyTypes());
        body.put("explicitOrderNos", criteria.orderNos());
        body.put("limit", limit);
        body.put("cursor", cursor == null ? "" : cursor);
        return send(properties.getFloworderOrderBaseUrl(), ORDER_PATH, body, traceId,
                FlowOrderOrderCandidates.class);
    }

    @Override
    public FlowOrderResourceEnrichment enrichResources(String discoveryRequestId,
                                                       List<String> requestIds,
                                                       List<String> deductNos,
                                                       IncidentScopeCriteria criteria,
                                                       String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("discoveryRequestId", discoveryRequestId);
        body.put("requestIds", requestIds);
        body.put("deductNos", deductNos);
        body.put("anomalyTypes", criteria.anomalyTypes());
        return send(properties.getFloworderBaseUrl(), RESOURCE_PATH, body, traceId,
                FlowOrderResourceEnrichment.class);
    }

    private <T> T send(String baseUrl, String path, Object body, String traceId, Class<T> type) {
        if (properties.getIncidentScopeInternalToken().isBlank()) {
            throw new FlowOrderApiException("FlowOrder incident scope internal token is not configured", 0, false);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(trim(baseUrl) + path))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header(INTERNAL_TOKEN_HEADER, properties.getIncidentScopeInternalToken())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                        StandardCharsets.UTF_8));
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Trace-Id", traceId.trim());
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new FlowOrderApiException(
                        "FlowOrder incident scope query failed: HTTP " + response.statusCode(),
                        response.statusCode(), response.statusCode() == 502 || response.statusCode() == 503);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = objectMapper.readValue(response.body(), Map.class);
            int code = envelope.get("code") instanceof Number number ? number.intValue() : 0;
            if (code != 200 || envelope.get("data") == null) {
                throw new FlowOrderApiException("FlowOrder incident scope business error", code, false);
            }
            return objectMapper.convertValue(envelope.get("data"), type);
        } catch (FlowOrderApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FlowOrderApiException("FlowOrder incident scope network failure", 0, true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowOrderApiException("FlowOrder incident scope query interrupted", 0, false, exception);
        } catch (RuntimeException exception) {
            throw new FlowOrderApiException("FlowOrder incident scope response contract mismatch", 200, false, exception);
        }
    }

    private String trim(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
