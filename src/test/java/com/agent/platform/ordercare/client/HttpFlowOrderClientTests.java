package com.agent.platform.ordercare.client;

import com.agent.platform.ordercare.config.OrderCareProperties;
import com.agent.platform.ordercare.model.OrderCareCaseSnapshot;
import com.agent.platform.ordercare.model.OrderCareProposalCreateCommand;
import com.agent.platform.ordercare.model.OrderCareProposalExecuteCommand;
import com.agent.platform.ordercare.model.OrderCareActionReconcileCommand;
import com.agent.platform.ordercare.model.OrderCareRecoveryProposal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void previewRetriesWithExactlyTheSameProposalId() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> firstBody = new AtomicReference<>();
        AtomicReference<String> secondBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/recovery/proposals", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                firstBody.set(body);
                exchange.sendResponseHeaders(503, -1);
            } else {
                secondBody.set(body);
                byte[] response = proposalBody().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();

        OrderCareProperties properties = properties();
        properties.setInspectMaxAttempts(2);
        properties.setInspectRetryBackoffMillis(0);
        HttpFlowOrderClient client = new HttpFlowOrderClient(properties, new ObjectMapper());

        OrderCareRecoveryProposal proposal = client.createProposal(
                new OrderCareProposalCreateCommand(
                        "prop-fixed-id",
                        "REQUEST_ID",
                        "request-1",
                        "REPLAY",
                        "diagnosed by agent"
                ),
                "trace-preview"
        );

        assertEquals(2, attempts.get());
        assertEquals(firstBody.get(), secondBody.get());
        assertTrue(firstBody.get().contains("prop-fixed-id"));
        assertEquals("act-fixed-id", proposal.actionRequestId());
        assertEquals("ACTIVE", proposal.proposalStatus());
    }

    @Test
    void executeNeverBlindlyRetriesAndClassifiesPossibleSideEffectAsUnknown() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/recovery/proposals/prop-fixed-id/execute", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        OrderCareProperties properties = properties();
        properties.setInspectMaxAttempts(3);
        HttpFlowOrderClient client = new HttpFlowOrderClient(properties, new ObjectMapper());

        FlowOrderApiException exception = assertThrows(FlowOrderApiException.class, () ->
                client.executeProposal(new OrderCareProposalExecuteCommand(
                        "prop-fixed-id",
                        1,
                        "fingerprint",
                        "effects",
                        "warnings",
                        "preview",
                        "approval-1",
                        "reviewer-1",
                        "approved",
                        "tool-exec-1"
                ), "trace-execute")
        );

        assertEquals(1, attempts.get());
        assertTrue(exception.outcomeUnknown());
        assertFalse(exception.retryable());
    }

    @Test
    void actionQueryReturnsLeaseAndCorrelationFacts() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/recovery/actions/act-fixed-id", exchange -> {
            byte[] body = actionSuccessBody().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var action = new HttpFlowOrderClient(properties(), new ObjectMapper())
                .getAction("act-fixed-id", "trace-action");

        assertEquals("act-fixed-id", action.actionRequestId());
        assertEquals("tool-exec-1", action.executionOwner());
        assertEquals("EXECUTING", action.actionStatus());
        assertEquals("WAITING_EXECUTION", action.reconciliationStatus());
    }

    @Test
    void reconciliationWriteIsSentOnceWhenResponseIsUnknown() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/recovery/actions/act-fixed-id/reconcile", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        FlowOrderApiException exception = assertThrows(FlowOrderApiException.class, () ->
                new HttpFlowOrderClient(properties(), new ObjectMapper()).reconcileAction(
                        "act-fixed-id",
                        new OrderCareActionReconcileCommand("tool-exec-1"),
                        "trace-reconcile"
                ));

        assertEquals(1, attempts.get());
        assertTrue(exception.outcomeUnknown());
        assertFalse(exception.retryable());
    }

    private OrderCareProperties properties() {
        OrderCareProperties properties = new OrderCareProperties();
        properties.setFloworderBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setReadTimeoutMillis(2_000);
        return properties;
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

    private String actionSuccessBody() {
        return """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "schemaVersion": "floworder-recovery-action-v1",
                    "proposalId": "prop-fixed-id",
                    "actionRequestId": "act-fixed-id",
                    "actionType": "REPLAY",
                    "targetType": "DEAD_LETTER",
                    "targetKey": "9",
                    "actionStatus": "EXECUTING",
                    "caseOutcome": "NOT_CONVERGED",
                    "reconciliationStatus": "WAITING_EXECUTION",
                    "executionOwner": "tool-exec-1",
                    "executionLeaseUntil": "2026-07-17T20:00:00",
                    "leaseExpired": false,
                    "reconcileCount": 0
                  }
                }
                """;
    }

    private String proposalBody() {
        return """
                {
                  "code": 200,
                  "message": "success",
                  "data": {
                    "schemaVersion": "floworder-recovery-proposal-v1",
                    "proposalId": "prop-fixed-id",
                    "proposalVersion": 1,
                    "proposalStatus": "ACTIVE",
                    "actionRequestId": "act-fixed-id",
                    "actionStatus": "NOT_STARTED",
                    "caseOutcome": "NOT_CONVERGED",
                    "caseKey": "floworder:request:request-1",
                    "identifierType": "REQUEST_ID",
                    "identifierValue": "request-1",
                    "actionType": "REPLAY",
                    "targetType": "DEAD_LETTER",
                    "targetKey": "101",
                    "stateFingerprint": "fingerprint",
                    "effectsDigest": "effects",
                    "warningsDigest": "warnings",
                    "previewDigest": "preview",
                    "canExecute": true,
                    "effects": ["replay"],
                    "warnings": ["approval required"],
                    "expiresAt": "2026-07-17T18:00:00"
                  }
                }
                """;
    }
}
