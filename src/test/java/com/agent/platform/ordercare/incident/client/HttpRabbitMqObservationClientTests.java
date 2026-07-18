package com.agent.platform.ordercare.incident.client;

import com.agent.platform.ordercare.incident.config.IncidentMqProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRabbitMqObservationClientTests {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void emitsOnlyQueueRuntimeSignals() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"messages_ready":80,"messages_unacknowledged":25,"consumers":0,"state":"running"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        IncidentMqProperties properties = new IncidentMqProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        properties.setMaxAttempts(1);
        var client = new HttpRabbitMqObservationClient(properties, new ObjectMapper());

        var observation = client.observeQueues(List.of("orders.dlq"), "trace-1");

        assertEquals("AVAILABLE", observation.status());
        assertEquals(List.of(
                "NO_ACTIVE_CONSUMER",
                "QUEUE_BACKLOG_HIGH",
                "UNACKNOWLEDGED_ABNORMAL"), observation.runtimeSignals());
        assertEquals(80, observation.queues().get(0).messagesReady());
        org.junit.jupiter.api.Assertions.assertTrue(authorization.get().startsWith("Basic "));
    }

    @Test
    void retriesTimeoutExactlyOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            try {
                Thread.sleep(500);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        IncidentMqProperties properties = new IncidentMqProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        properties.setReadTimeoutMillis(100);
        properties.setMaxAttempts(2);
        properties.setRetryBackoffMillis(0);
        var client = new HttpRabbitMqObservationClient(properties, new ObjectMapper());

        RabbitMqObservationException exception = assertThrows(
                RabbitMqObservationException.class,
                () -> client.observeQueues(List.of("orders.dlq"), "trace-timeout"));

        assertTrue(exception.timeout());
        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotReportMissingConsumerWhenQueueHasNoReadyMessages() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    {"messages_ready":0,"messages_unacknowledged":0,"consumers":0,"state":"running"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        IncidentMqProperties properties = new IncidentMqProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        properties.setMaxAttempts(1);
        var client = new HttpRabbitMqObservationClient(properties, new ObjectMapper());

        var observation = client.observeQueues(List.of("orders.dlq"), "trace-empty");

        assertEquals(List.of(), observation.runtimeSignals());
    }
}
