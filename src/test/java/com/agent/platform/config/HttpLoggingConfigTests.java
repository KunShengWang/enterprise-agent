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
        assertTrue(output.getOut().contains("[LLM][FULL HTTP REQUEST JSON]"));
        assertTrue(output.getOut().contains("[LLM][FULL HTTP RESPONSE JSON]"));
    }

    @Test
    void logsOneCompleteStreamingResponseJsonWithoutConsumingSse(CapturedOutput output) throws Exception {
        String requestJson = """
                {"model":"deepseek-chat","messages":[{"role":"user","content":"stream"}],"stream":true}
                """.strip();
        String firstEvent = "data: {\"id\":\"chat-2\",\"object\":\"chat.completion.chunk\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"part-1\"},\"finish_reason\":null}]}\n\n";
        String secondEvent = "data: {\"id\":\"chat-2\",\"object\":\"chat.completion.chunk\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"part-2\"},\"finish_reason\":\"stop\"}],\"usage\":{\"total_tokens\":12}}\n\n";
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
        assertTrue(output.getOut().contains("[LLM][FULL HTTP RESPONSE JSON]"));
        assertTrue(output.getOut().contains("transport=sse reconstructed=true"));
        assertTrue(output.getOut().contains("\"content\":\"part-1part-2\""));
        assertTrue(output.getOut().contains("\"finish_reason\":\"stop\""));
        assertTrue(output.getOut().contains("\"total_tokens\":12"));
        assertEquals(1, occurrences(output.getOut(), "[LLM][FULL HTTP RESPONSE JSON]"));
        assertEquals(0, occurrences(output.getOut(), "[LLM][RAW HTTP RESPONSE BODY]"));
    }

    @Test
    void reconstructsNativeStreamingToolCallsInCompleteHttpJson(CapturedOutput output) throws Exception {
        String requestJson = """
                {"model":"deepseek-chat","messages":[{"role":"user","content":"status"}],"tools":[{"type":"function","function":{"name":"ticket_status","parameters":{"type":"object"}}}],"stream":true}
                """.strip();
        String firstEvent = "data: {\"id\":\"chat-tool\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"ticket_\",\"arguments\":\"{\\\"id\\\":\"}}]},\"finish_reason\":null}]}\n\n";
        String secondEvent = "data: {\"id\":\"chat-tool\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"status\",\"arguments\":\"\\\"T1\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n";
        String done = "data: [DONE]\n\n";
        startServer("/tool-stream", exchange -> {
            readBody(exchange);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(firstEvent.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().write(secondEvent.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().write(done.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });

        List<String> events = new HttpLoggingConfig().llmWebClientBuilder()
                .build()
                .post()
                .uri(url("/tool-stream"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestJson)
                .retrieve()
                .bodyToFlux(String.class)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(3, events == null ? 0 : events.size());
        assertTrue(output.getOut().contains("\"name\":\"ticket_status\""));
        assertTrue(output.getOut().contains("\\\"id\\\":\\\"T1\\\""));
        assertTrue(output.getOut().contains("\"finish_reason\":\"tool_calls\""));
        assertEquals(1, occurrences(output.getOut(), "[LLM][FULL HTTP RESPONSE JSON]"));
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

    private int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
