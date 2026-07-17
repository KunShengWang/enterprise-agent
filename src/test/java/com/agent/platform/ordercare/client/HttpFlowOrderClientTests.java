package com.agent.platform.ordercare.client;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpFlowOrderClientTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsVersionedContractAndPropagatesTraceId() throws Exception {
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/recovery/cases/inspect", exchange -> {
            traceId.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = successBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        OrderCareProperties properties = new OrderCareProperties();
        properties.setFloworderBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInspectMaxAttempts(1);
        HttpFlowOrderClient client = new HttpFlowOrderClient(properties, new ObjectMapper());

        OrderCareCaseSnapshot snapshot = client.inspectCase("REQUEST_ID", "request 1", "run-trace-1");

        assertEquals("REPLAY_CANDIDATE", snapshot.diagnosisCode());
        assertEquals("floworder:request:request-1", snapshot.caseKey());
        assertTrue(snapshot.recoveryEligible());
        assertEquals("run-trace-1", traceId.get());
        assertTrue(query.get().contains("identifierValue=request+1"));
    }

    private String successBody() {
        return """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "schemaVersion": "floworder-recovery-case-v1",
                    "caseKey": "floworder:request:request-1",
                    "identifierType": "REQUEST_ID",
                    "identifierValue": "request-1",
                    "canonicalRequestId": "request-1",
                    "found": true,
                    "diagnosisCode": "REPLAY_CANDIDATE",
                    "factsComplete": true,
                    "recoveryEligible": true,
                    "deadLetters": [],
                    "recoveryActions": [],
                    "candidates": [],
                    "evidence": ["ORDER_FOUND"],
                    "hardRisks": []
                  }
                }
                """;
    }
}
