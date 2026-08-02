package com.agent.platform.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class HttpLoggingConfigTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void logsAndPreservesNativeSynchronousJson(CapturedOutput output) throws Exception {
        String requestJson = """
                {"model":"deepseek-chat","messages":[{"role":"user","content":"raw request"}]}
                """.strip();
        String responseJson = """
                {"id":"chat-1","choices":[{"message":{"role":"assistant","content":"raw response"}}]}
                """.strip();
        AtomicReference<String> receivedRequest = new AtomicReference<>();
        startServer("/sync", exchange -> {
            receivedRequest.set(readBody(exchange));
            send(exchange, "application/json", responseJson);
        });

        String response = new HttpLoggingConfig().llmRestClientBuilder()
                .build()
                .post()
                .uri(url("/sync"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .body(String.class);

        assertEquals(requestJson, receivedRequest.get());
        assertEquals(responseJson, response);
        assertTrue(output.getOut().contains(requestJson));
        assertTrue(output.getOut().contains(responseJson));
        assertTrue(output.getOut().contains("[LLM][RAW HTTP REQUEST]"));
        assertTrue(output.getOut().contains("[LLM][RAW HTTP RESPONSE]"));
    }

    @Test
    void logsRawStreamingSseWithoutConsumingIt(CapturedOutput output) throws Exception {
        String requestJson = """
                {"model":"deepseek-chat","messages":[{"role":"user","content":"stream"}],"stream":true}
                """.strip();
        String firstEvent = "data: {\"id\":\"chat-2\",\"choices\":[{\"delta\":{\"content\":\"part-1\"}}]}\n\n";
        String secondEvent = "data: {\"id\":\"chat-2\",\"choices\":[{\"delta\":{\"content\":\"part-2\"}}]}\n\n";
        String done = "data: [DONE]\n\n";
        AtomicReference<String> receivedRequest = new AtomicReference<>();
        startServer("/stream", exchange -> {
            receivedRequest.set(readBody(exchange));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(firstEvent.substring(0, 17).getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.getResponseBody().write(firstEvent.substring(17).getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().write(secondEvent.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().write(done.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });

        List<String> events = new HttpLoggingConfig().llmWebClientBuilder()
                .build()
                .post()
                .uri(url("/stream"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .retrieve()
                .bodyToFlux(String.class)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(requestJson, receivedRequest.get());
        assertEquals(3, events == null ? 0 : events.size());
        assertTrue(output.getOut().contains(requestJson));
        assertTrue(output.getOut().contains(firstEvent.trim()));
        assertTrue(output.getOut().contains(secondEvent.trim()));
        assertTrue(output.getOut().contains(done.trim()));
        assertTrue(output.getOut().contains("[LLM][RAW HTTP RESPONSE BODY]"));
    }

    private void startServer(String path, ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            }
            finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void send(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
