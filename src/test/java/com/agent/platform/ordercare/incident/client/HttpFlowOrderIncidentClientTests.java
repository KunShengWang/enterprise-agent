package com.agent.platform.ordercare.incident.client;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.incident.model.IncidentFactQuery;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HttpFlowOrderIncidentClientTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsDeadLetterCountDimensionsAndPropagatesScope() throws Exception {
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/incidents/facts/dead-letters", exchange -> {
            traceId.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OrderCareProperties properties = new OrderCareProperties();
        properties.setFloworderBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInspectMaxAttempts(1);
        var client = new HttpFlowOrderIncidentClient(properties, new ObjectMapper());
        var result = client.queryDeadLetterFacts(new IncidentFactQuery(
                "inc-1",
                "snap-1",
                "scope-1",
                List.of("REQ-1", "REQ-2"),
                List.of("orders.dlq"),
                500), "trace-1");

        assertEquals("trace-1", traceId.get());
        assertEquals("scope-1", result.scopeHash());
        assertFalse(result.truncated());
        assertEquals(3, result.facts().recordCount());
        assertEquals(2, result.facts().distinctBizKeyCount());
        assertEquals(2, result.facts().distinctRequestIdCount());
        assertEquals(1, result.facts().duplicateRecordCount());
        assertEquals(List.of("DEDUCT-1", "DEDUCT-2"), result.facts().bizKeys());
        org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"snapshotId\":\"snap-1\""));
    }

    private String responseBody() {
        return """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "schemaVersion": "floworder-incident-facts-v1",
                    "sourceSystem": "floworder-resource-service",
                    "sourceReference": "incident/dead-letter-facts/inc-1",
                    "scopeHash": "scope-1",
                    "observedAt": "2026-07-18T18:00:00+08:00",
                    "truncated": false,
                    "missingRequestIds": [],
                    "facts": {
                      "recordCount": 3,
                      "totalMatchingRecordCount": 3,
                      "distinctBizKeyCount": 2,
                      "distinctRequestIdCount": 2,
                      "duplicateRecordCount": 1,
                      "unmappedRecordCount": 0,
                      "bizKeys": ["DEDUCT-1", "DEDUCT-2"],
                      "requestIds": ["REQ-1", "REQ-2"],
                      "deadLetterIds": [11, 12, 13],
                      "duplicateGroups": [],
                      "items": []
                    }
                  }
                }
                """;
    }
}
