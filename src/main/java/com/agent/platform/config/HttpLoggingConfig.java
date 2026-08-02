package com.agent.platform.config;

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
import java.util.Optional;
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
            log.info("[LLM][RAW HTTP REQUEST] callId={} method={} uri={}\n{}",
                    callId,
                    request.getMethod(),
                    request.getURI(),
                    utf8(body));

            ClientHttpResponse response = execution.execute(request, body);
            BufferedClientHttpResponse buffered = new BufferedClientHttpResponse(response);
            log.info("[LLM][RAW HTTP RESPONSE] callId={} status={}\n{}",
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
                        log.info("[LLM][RAW HTTP RESPONSE] callId={} status={}",
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
                            log.info("[LLM][RAW HTTP REQUEST] callId={} method={} uri={}",
                                    callId, getMethod(), getURI());
                            return super.setComplete();
                        }
                        DataBuffer buffer = joined.orElseThrow();
                        byte[] bytes = readAndRelease(buffer);
                        log.info("[LLM][RAW HTTP REQUEST] callId={} method={} uri={}\n{}",
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

        private final RawLineLogger bodyLogger;

        private LoggingClientHttpResponse(
                org.springframework.http.client.reactive.ClientHttpResponse delegate,
                String callId) {
            super(delegate);
            this.bodyLogger = new RawLineLogger(callId);
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
     * Reassembles transport fragments into complete raw SSE lines. It does not
     * deserialize or modify the JSON carried by each {@code data:} line.
     */
    private static final class RawLineLogger {

        private final String callId;
        private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream();

        private RawLineLogger(String callId) {
            this.callId = callId;
        }

        private synchronized void accept(DataBuffer buffer) {
            ByteBuffer bytes = buffer.toByteBuffer();
            while (bytes.hasRemaining()) {
                byte value = bytes.get();
                if (value == '\n') {
                    emitLine();
                }
                else {
                    currentLine.write(value);
                }
            }
        }

        private synchronized void complete() {
            if (currentLine.size() > 0) {
                emitLine();
            }
        }

        private void emitLine() {
            byte[] bytes = currentLine.toByteArray();
            currentLine.reset();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            if (length > 0) {
                log.info("[LLM][RAW HTTP RESPONSE BODY] callId={}\n{}",
                        callId, new String(bytes, 0, length, StandardCharsets.UTF_8));
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
