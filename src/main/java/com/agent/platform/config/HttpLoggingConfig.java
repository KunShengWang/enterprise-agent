package com.agent.platform.config;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.http.client.reactive.ClientHttpResponseDecorator;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Logs the provider-native HTTP payload before Spring AI deserializes it.
 *
 * <p>The synchronous DeepSeek call uses {@link RestClient}; streaming uses
 * {@link WebClient}. Both builders are supplied here so requests and responses
 * are captured after JSON serialization and before JSON/SSE decoding.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "llm.http-log", name = "enabled", havingValue = "true")
public class HttpLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpLoggingConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong CALL_SEQUENCE = new AtomicLong();

    @Bean
    public RestClient.Builder llmRestClientBuilder() {
        return RestClient.builder().requestInterceptor(synchronousLoggingInterceptor());
    }

    @Bean
    public WebClient.Builder llmWebClientBuilder() {
        return WebClient.builder()
                .clientConnector(new RawLoggingClientHttpConnector(new ReactorClientHttpConnector()));
    }

    private static ClientHttpRequestInterceptor synchronousLoggingInterceptor() {
        return (request, body, execution) -> {
            String callId = nextCallId();
            log.info("[LLM][FULL HTTP REQUEST JSON] callId={} method={} uri={}\n{}",
                    callId,
                    request.getMethod(),
                    request.getURI(),
                    utf8(body));

            ClientHttpResponse response = execution.execute(request, body);
            BufferedClientHttpResponse buffered = new BufferedClientHttpResponse(response);
            log.info("[LLM][FULL HTTP RESPONSE JSON] callId={} status={} transport=json\n{}",
                    callId,
                    buffered.getStatusCode(),
                    utf8(buffered.body));
            return buffered;
        };
    }

    private static String nextCallId() {
        return "llm-http-" + CALL_SEQUENCE.incrementAndGet();
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8);
    }

    private static final class BufferedClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        private BufferedClientHttpResponse(ClientHttpResponse delegate) throws IOException {
            this.delegate = delegate;
            try (InputStream input = delegate.getBody()) {
                this.body = input.readAllBytes();
            }
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }

    static final class RawLoggingClientHttpConnector implements ClientHttpConnector {

        private final ClientHttpConnector delegate;

        RawLoggingClientHttpConnector(ClientHttpConnector delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<org.springframework.http.client.reactive.ClientHttpResponse> connect(
                HttpMethod method,
                URI uri,
                Function<? super ClientHttpRequest, Mono<Void>> requestCallback) {
            String callId = nextCallId();
            return delegate.connect(method, uri, request ->
                            requestCallback.apply(new LoggingClientHttpRequest(request, callId)))
                    .map(response -> {
                        log.info("[LLM][HTTP RESPONSE STATUS] callId={} status={}",
                                callId, response.getStatusCode());
                        return new LoggingClientHttpResponse(response, callId);
                    });
        }
    }

    private static final class LoggingClientHttpRequest extends ClientHttpRequestDecorator {

        private final String callId;

        private LoggingClientHttpRequest(ClientHttpRequest delegate, String callId) {
            super(delegate);
            this.callId = callId;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(joined -> {
                        if (joined.isEmpty()) {
                            log.info("[LLM][FULL HTTP REQUEST JSON] callId={} method={} uri={}",
                                    callId, getMethod(), getURI());
                            return super.setComplete();
                        }
                        DataBuffer buffer = joined.orElseThrow();
                        byte[] bytes = readAndRelease(buffer);
                        log.info("[LLM][FULL HTTP REQUEST JSON] callId={} method={} uri={}\n{}",
                                callId, getMethod(), getURI(), utf8(bytes));
                        return super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
                    });
        }

        @Override
        public Mono<Void> writeAndFlushWith(
                Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).concatMap(Flux::from));
        }
    }

    private static final class LoggingClientHttpResponse extends ClientHttpResponseDecorator {

        private final CompleteResponseLogger bodyLogger;

        private LoggingClientHttpResponse(
                org.springframework.http.client.reactive.ClientHttpResponse delegate,
                String callId) {
            super(delegate);
            this.bodyLogger = new CompleteResponseLogger(callId);
        }

        @Override
        public Flux<DataBuffer> getBody() {
            return super.getBody()
                    .doOnNext(bodyLogger::accept)
                    .doOnComplete(bodyLogger::complete)
                    .doOnError(ignored -> bodyLogger.complete());
        }
    }

    /**
     * Observes the response without consuming it. A regular JSON response is
     * logged unchanged; an SSE response is reconstructed into one complete,
     * OpenAI-compatible response JSON before it is logged.
     */
    private static final class CompleteResponseLogger {

        private final String callId;
        private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
        private final ByteArrayOutputStream rawBody = new ByteArrayOutputStream();
        private final ObjectNode response = JSON.createObjectNode();
        private final Map<Integer, ObjectNode> choices = new TreeMap<>();
        private boolean streaming;
        private boolean logged;

        private CompleteResponseLogger(String callId) {
            this.callId = callId;
        }

        private synchronized void accept(DataBuffer buffer) {
            ByteBuffer bytes = buffer.toByteBuffer();
            while (bytes.hasRemaining()) {
                byte value = bytes.get();
                rawBody.write(value);
                if (value == '\n') {
                    consumeLine();
                }
                else {
                    currentLine.write(value);
                }
            }
        }

        private synchronized void complete() {
            if (logged) {
                return;
            }
            if (currentLine.size() > 0) {
                consumeLine();
            }
            logged = true;

            if (streaming) {
                log.info("[LLM][FULL HTTP RESPONSE JSON] callId={} transport=sse reconstructed=true\n{}",
                        callId, assembledResponseJson());
                return;
            }

            String body = rawBody.toString(StandardCharsets.UTF_8).trim();
            if (!body.isEmpty()) {
                log.info("[LLM][FULL HTTP RESPONSE JSON] callId={} transport=json\n{}", callId, body);
            }
        }

        private void consumeLine() {
            byte[] bytes = currentLine.toByteArray();
            currentLine.reset();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            if (length == 0) {
                return;
            }

            String line = new String(bytes, 0, length, StandardCharsets.UTF_8);
            if (!line.startsWith("data:")) {
                return;
            }

            streaming = true;
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return;
            }

            try {
                mergeEvent(JSON.readTree(payload));
            }
            catch (JacksonException exception) {
                log.warn("[LLM][HTTP RESPONSE LOGGING] callId={} ignored malformed SSE data", callId);
            }
        }

        private void mergeEvent(JsonNode event) {
            copy(event, response, "id");
            copy(event, response, "created");
            copy(event, response, "model");
            copy(event, response, "system_fingerprint");
            copy(event, response, "service_tier");
            copy(event, response, "usage");

            JsonNode object = event.get("object");
            if (object != null && object.isTextual()) {
                response.put("object", object.asText().replace(".chunk", ""));
            }

            JsonNode eventChoices = event.get("choices");
            if (eventChoices == null || !eventChoices.isArray()) {
                return;
            }
            for (JsonNode eventChoice : eventChoices) {
                int index = eventChoice.path("index").asInt(choices.size());
                ObjectNode choice = choices.computeIfAbsent(index, ignored -> {
                    ObjectNode created = JSON.createObjectNode();
                    created.put("index", index);
                    created.set("message", JSON.createObjectNode());
                    return created;
                });
                JsonNode delta = eventChoice.get("delta");
                if (delta != null && delta.isObject()) {
                    mergeObject((ObjectNode) choice.get("message"), (ObjectNode) delta);
                }
                copy(eventChoice, choice, "logprobs");
                if (eventChoice.hasNonNull("finish_reason")) {
                    choice.set("finish_reason", eventChoice.get("finish_reason").deepCopy());
                }
            }
        }

        private String assembledResponseJson() {
            ArrayNode assembledChoices = JSON.createArrayNode();
            choices.values().forEach(assembledChoices::add);
            response.set("choices", assembledChoices);
            try {
                return JSON.writeValueAsString(response);
            }
            catch (JacksonException exception) {
                return "{\"loggingError\":\"Unable to serialize reconstructed LLM response\"}";
            }
        }

        private static void mergeObject(ObjectNode target, ObjectNode fragment) {
            fragment.properties().forEach(entry -> {
                String name = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isTextual() && shouldAppend(name)) {
                    target.put(name, target.path(name).asText("") + value.asText());
                }
                else if (value.isObject()) {
                    JsonNode existing = target.get(name);
                    ObjectNode child = existing != null && existing.isObject()
                            ? (ObjectNode) existing
                            : target.putObject(name);
                    mergeObject(child, (ObjectNode) value);
                }
                else if (value.isArray() && "tool_calls".equals(name)) {
                    mergeToolCalls(target.withArray(name), (ArrayNode) value);
                }
                else if (!value.isNull()) {
                    target.set(name, value.deepCopy());
                }
            });
        }

        private static void mergeToolCalls(ArrayNode target, ArrayNode fragments) {
            for (JsonNode fragment : fragments) {
                int index = fragment.path("index").asInt(target.size());
                while (target.size() <= index) {
                    target.addObject();
                }
                ObjectNode call = (ObjectNode) target.get(index);
                mergeObject(call, (ObjectNode) fragment);
            }
        }

        private static boolean shouldAppend(String fieldName) {
            return "content".equals(fieldName)
                    || "reasoning_content".equals(fieldName)
                    || "arguments".equals(fieldName)
                    || "name".equals(fieldName);
        }

        private static void copy(JsonNode source, ObjectNode target, String fieldName) {
            if (source.has(fieldName)) {
                target.set(fieldName, source.get(fieldName).deepCopy());
            }
        }
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        }
        finally {
            DataBufferUtils.release(buffer);
        }
    }
}
