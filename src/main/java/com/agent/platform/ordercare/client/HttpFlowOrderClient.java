package com.agent.platform.ordercare.client;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
public class HttpFlowOrderClient implements FlowOrderClient {

    private static final String INSPECT_PATH = "/internal/recovery/cases/inspect";
    private static final String PROPOSALS_PATH = "/internal/recovery/proposals";

    private final OrderCareProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public HttpFlowOrderClient(OrderCareProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build());
    }

    HttpFlowOrderClient(OrderCareProperties properties,
                        ObjectMapper objectMapper,
                        HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public OrderCareCaseSnapshot inspectCase(String identifierType,
                                             String identifierValue,
                                             String traceId) {
        FlowOrderApiException lastFailure = null;
        int maxAttempts = properties.getInspectMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        inspectRequest(identifierType, identifierValue, traceId),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() == 502 || response.statusCode() == 503) {
                    lastFailure = new FlowOrderApiException(
                            "FlowOrder inspect temporarily unavailable: HTTP " + response.statusCode(),
                            response.statusCode(),
                            true
                    );
                } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new FlowOrderApiException(
                            "FlowOrder inspect rejected: HTTP " + response.statusCode(),
                            response.statusCode(),
                            false
                    );
                } else {
                    return decode(response.body());
                }
            } catch (IOException exception) {
                lastFailure = new FlowOrderApiException(
                        "FlowOrder inspect network failure",
                        0,
                        true,
                        exception
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new FlowOrderApiException("FlowOrder inspect interrupted", 0, false, exception);
            }
            if (lastFailure == null || !lastFailure.retryable() || attempt >= maxAttempts) {
                break;
            }
            backoff(attempt);
        }
        throw lastFailure == null
                ? new FlowOrderApiException("FlowOrder inspect failed", 0, false)
                : lastFailure;
    }

    @Override
    public OrderCareRecoveryProposal createProposal(OrderCareProposalCreateCommand command, String traceId) {
        HttpRequest request = postRequest(PROPOSALS_PATH, command, traceId);
        return sendIdempotentProposal(request, "preview");
    }

    @Override
    public OrderCareRecoveryProposal getProposal(String proposalId, String traceId) {
        String path = PROPOSALS_PATH + "/" + encode(proposalId);
        HttpRequest request = requestBuilder(path, traceId)
                .header("Accept", "application/json")
                .GET()
                .build();
        return sendIdempotentProposal(request, "proposal query");
    }

    @Override
    public OrderCareRecoveryProposal executeProposal(OrderCareProposalExecuteCommand command, String traceId) {
        String path = PROPOSALS_PATH + "/" + encode(command == null ? "" : command.proposalId()) + "/execute";
        HttpRequest request = postRequest(path, command, traceId);
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return decodeProposal(response.body());
            }
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new FlowOrderApiException(
                        "FlowOrder execute rejected: HTTP " + response.statusCode(),
                        response.statusCode(),
                        false
                );
            }
            throw new FlowOrderApiException(
                    "FlowOrder execute outcome unknown: HTTP " + response.statusCode(),
                    response.statusCode(),
                    false,
                    true,
                    null
            );
        } catch (IOException exception) {
            throw new FlowOrderApiException(
                    "FlowOrder execute outcome unknown after network failure",
                    0,
                    false,
                    true,
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowOrderApiException(
                    "FlowOrder execute interrupted with unknown outcome",
                    0,
                    false,
                    true,
                    exception
            );
        }
    }

    private OrderCareRecoveryProposal sendIdempotentProposal(HttpRequest request, String operation) {
        FlowOrderApiException lastFailure = null;
        int maxAttempts = properties.getInspectMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() == 502 || response.statusCode() == 503) {
                    lastFailure = new FlowOrderApiException(
                            "FlowOrder " + operation + " temporarily unavailable: HTTP " + response.statusCode(),
                            response.statusCode(),
                            true
                    );
                } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new FlowOrderApiException(
                            "FlowOrder " + operation + " rejected: HTTP " + response.statusCode(),
                            response.statusCode(),
                            false
                    );
                } else {
                    return decodeProposal(response.body());
                }
            } catch (IOException exception) {
                lastFailure = new FlowOrderApiException(
                        "FlowOrder " + operation + " network failure",
                        0,
                        true,
                        exception
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new FlowOrderApiException("FlowOrder " + operation + " interrupted", 0, false, exception);
            }
            if (lastFailure == null || !lastFailure.retryable() || attempt >= maxAttempts) {
                break;
            }
            backoff(attempt);
        }
        throw lastFailure == null
                ? new FlowOrderApiException("FlowOrder " + operation + " failed", 0, false)
                : lastFailure;
    }

    private HttpRequest inspectRequest(String identifierType,
                                       String identifierValue,
                                       String traceId) {
        String baseUrl = trimTrailingSlash(properties.getFloworderBaseUrl());
        String query = "identifierType=" + encode(identifierType)
                + "&identifierValue=" + encode(identifierValue);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + INSPECT_PATH + "?" + query))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .header("Accept", "application/json")
                .GET();
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Trace-Id", traceId.trim());
        }
        return builder.build();
    }

    private HttpRequest postRequest(String path, Object body, String traceId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (RuntimeException exception) {
            throw new FlowOrderApiException("serialize FlowOrder request failed", 0, false, exception);
        }
        return requestBuilder(path, traceId)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest.Builder requestBuilder(String path, String traceId) {
        String baseUrl = trimTrailingSlash(properties.getFloworderBaseUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Trace-Id", traceId.trim());
        }
        return builder;
    }

    private OrderCareCaseSnapshot decode(String body) {
        try {
            Map<?, ?> envelope = objectMapper.readValue(body, Map.class);
            int code = envelope.get("code") instanceof Number number ? number.intValue() : 0;
            if (code != 200) {
                throw new FlowOrderApiException(
                        "FlowOrder inspect business error: " + String.valueOf(envelope.get("message")),
                        code,
                        false
                );
            }
            Object data = envelope.get("data");
            if (data == null) {
                throw new FlowOrderApiException("FlowOrder inspect returned empty data", code, false);
            }
            return objectMapper.convertValue(data, OrderCareCaseSnapshot.class);
        } catch (FlowOrderApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FlowOrderApiException("FlowOrder inspect response contract mismatch", 200, false, exception);
        }
    }

    private OrderCareRecoveryProposal decodeProposal(String body) {
        try {
            Map<?, ?> envelope = objectMapper.readValue(body, Map.class);
            int code = envelope.get("code") instanceof Number number ? number.intValue() : 0;
            if (code != 200) {
                throw new FlowOrderApiException(
                        "FlowOrder proposal business error: " + String.valueOf(envelope.get("message")),
                        code,
                        false
                );
            }
            Object data = envelope.get("data");
            if (data == null) {
                throw new FlowOrderApiException("FlowOrder proposal returned empty data", code, false);
            }
            return objectMapper.convertValue(data, OrderCareRecoveryProposal.class);
        } catch (FlowOrderApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FlowOrderApiException("FlowOrder proposal response contract mismatch", 200, false, exception);
        }
    }

    private void backoff(int attempt) {
        long delay = properties.getInspectRetryBackoffMillis() * attempt;
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FlowOrderApiException("FlowOrder inspect retry interrupted", 0, false, exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("enterprise-agent.ordercare.floworder-base-url must not be blank");
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
