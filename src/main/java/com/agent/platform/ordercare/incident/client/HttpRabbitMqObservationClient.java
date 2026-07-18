package com.agent.platform.ordercare.incident.client;

import com.agent.platform.ordercare.incident.config.IncidentMqProperties;
import com.agent.platform.ordercare.incident.model.BrokerObservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.locks.LockSupport;

@Component
public class HttpRabbitMqObservationClient implements RabbitMqObservationClient {

    private final IncidentMqProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public HttpRabbitMqObservationClient(IncidentMqProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build());
    }

    HttpRabbitMqObservationClient(IncidentMqProperties properties,
                                  ObjectMapper objectMapper,
                                  HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public BrokerObservation observeQueues(List<String> queueNames, String traceId) {
        List<String> normalizedQueueNames = normalizeQueueNames(queueNames);
        RabbitMqObservationException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                return observeOnce(normalizedQueueNames, traceId);
            } catch (RabbitMqObservationException exception) {
                lastFailure = exception;
                if (attempt >= properties.getMaxAttempts()) {
                    break;
                }
                backoff(attempt);
            }
        }
        throw lastFailure == null
                ? new RabbitMqObservationException("RabbitMQ observation failed", false, null)
                : lastFailure;
    }

    private BrokerObservation observeOnce(List<String> queueNames, String traceId) {
        List<BrokerObservation.QueueRuntimeFact> queues = new ArrayList<>();
        TreeSet<String> signals = new TreeSet<>();
        for (String queueName : queueNames) {
            HttpResponse<String> response = send(queueName, traceId);
            if (response.statusCode() == 404) {
                queues.add(new BrokerObservation.QueueRuntimeFact(queueName, null, null, null, "NOT_FOUND"));
                signals.add("QUEUE_NOT_FOUND");
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RabbitMqObservationException(
                        "RabbitMQ Management rejected queue observation: HTTP " + response.statusCode(),
                        false,
                        null);
            }
            BrokerObservation.QueueRuntimeFact fact = decode(queueName, response.body());
            queues.add(fact);
            addSignals(fact, signals);
        }
        queues.sort(Comparator.comparing(BrokerObservation.QueueRuntimeFact::queueName));
        return new BrokerObservation(
                "AVAILABLE",
                OffsetDateTime.now(),
                queues,
                List.copyOf(signals),
                "");
    }

    private HttpResponse<String> send(String queueName, String traceId) {
        String baseUrl = trimTrailingSlash(properties.getBaseUrl());
        String path = "/queues/" + pathSegment(properties.getVirtualHost()) + "/" + pathSegment(queueName);
        String credentials = properties.getUsername() + ":" + properties.getPassword();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMillis()))
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .GET();
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Trace-Id", traceId.trim());
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException | SocketTimeoutException exception) {
            throw new RabbitMqObservationException("RabbitMQ Management observation timed out", true, exception);
        } catch (ConnectException exception) {
            throw new RabbitMqObservationException("RabbitMQ Management connection failed", false, exception);
        } catch (IOException exception) {
            throw new RabbitMqObservationException("RabbitMQ Management network failure", false, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RabbitMqObservationException("RabbitMQ Management observation interrupted", false, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private BrokerObservation.QueueRuntimeFact decode(String queueName, String body) {
        try {
            Map<String, Object> data = objectMapper.readValue(body, Map.class);
            return new BrokerObservation.QueueRuntimeFact(
                    queueName,
                    integer(data.get("messages_ready")),
                    integer(data.get("messages_unacknowledged")),
                    integer(data.get("consumers")),
                    data.get("state") == null ? "UNKNOWN" : String.valueOf(data.get("state")));
        } catch (RuntimeException exception) {
            throw new RabbitMqObservationException(
                    "RabbitMQ Management response contract mismatch",
                    false,
                    exception);
        }
    }

    private void addSignals(BrokerObservation.QueueRuntimeFact fact, TreeSet<String> signals) {
        if (fact.messagesReady() != null && fact.messagesReady() >= properties.getBacklogThreshold()) {
            signals.add("QUEUE_BACKLOG_HIGH");
        }
        if (fact.messagesReady() != null
                && fact.messagesReady() > 0
                && fact.consumerCount() != null
                && fact.consumerCount() == 0) {
            signals.add("NO_ACTIVE_CONSUMER");
        }
        if (fact.messagesUnacknowledged() != null
                && fact.messagesUnacknowledged() >= properties.getUnacknowledgedThreshold()) {
            signals.add("UNACKNOWLEDGED_ABNORMAL");
        }
    }

    private List<String> normalizeQueueNames(List<String> queueNames) {
        if (queueNames == null || queueNames.isEmpty()) {
            throw new IllegalArgumentException("queueNames must not be empty");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String queueName : queueNames) {
            if (queueName == null || queueName.isBlank()) {
                throw new IllegalArgumentException("queueNames must not contain blank values");
            }
            normalized.add(queueName.trim());
        }
        if (normalized.size() > 20) {
            throw new IllegalArgumentException("queueNames size must not exceed 20");
        }
        return List.copyOf(normalized);
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void backoff(int attempt) {
        long millis = properties.getRetryBackoffMillis() * attempt;
        if (millis > 0) {
            LockSupport.parkNanos(Duration.ofMillis(millis).toNanos());
        }
    }
}
