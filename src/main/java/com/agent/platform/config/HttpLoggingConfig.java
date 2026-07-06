package com.agent.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 自定义 RestClient.Builder，给 Spring AI 的 ChatModel 调用加 HTTP 拦截器。
 * 同时拦截请求和响应，打印发给 LLM 的 HTTP 请求体和 LLM 返回的 HTTP 响应体。
 *
 * 响应体是流式的，读一次就消费完，所以用 BufferingClientHttpResponseWrapper
 * 把响应体缓存到 byte[]，让拦截器读一次后还能让 Spring AI 再读一次。
 *
 * 这是 super-agent 项目里 ObservedChatModelService 的最小教学版：
 * 拦截请求/响应 → 打印/记录 → 继续执行
 */
@Configuration
@ConditionalOnProperty(prefix = "llm.http-log", name = "enabled", havingValue = "true")
public class HttpLoggingConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestInterceptor(loggingInterceptor());
    }

    private static ClientHttpRequestInterceptor loggingInterceptor() {
        return (request, body, execution) -> {
            System.out.println("\n========== 实际发给 LLM 的 HTTP 请求 ==========");
            System.out.println("Method : " + request.getMethod());
            System.out.println("URI    : " + request.getURI());
            System.out.println("Headers: ");
            request.getHeaders().forEach((name, values) -> {
                String lowerName = name.toLowerCase();
                if (lowerName.contains("authorization")
                        || lowerName.contains("api-key")
                        || lowerName.contains("token")
                        || lowerName.contains("secret")) {
                    System.out.println("  " + name + ": [HIDDEN]");
                } else {
                    System.out.println("  " + name + ": " + values);
                }
            });
            System.out.println("Body   :");
            System.out.println(new String(body, StandardCharsets.UTF_8));
            System.out.println("==============================================");

            // 真正执行 HTTP 请求
            ClientHttpResponse response = execution.execute(request, body);
            // 用缓冲包装器把响应体读进内存，这样拦截器读一次后下游还能再读
            BufferedResponse buffered = new BufferedResponse(response);
            System.out.println("\n========== LLM 返回的 HTTP 响应 ==========");
            System.out.println("Status : " + buffered.getStatusCode());
            System.out.println("Headers: ");
            buffered.getHeaders().forEach((name, values) ->
                    System.out.println("  " + name + ": " + values));
            System.out.println("Body   :");
            System.out.println(new String(buffered.bufferedBody, StandardCharsets.UTF_8));
            System.out.println("==============================================\n");
            return buffered;
        };
    }

    /**
     * 响应包装器：把原始响应体读进 byte[] 缓存，getBody() 每次返回新的 ByteArrayInputStream。
     * 这样拦截器读完响应体后，Spring AI 还能再读一次。
     */
    private static class BufferedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] bufferedBody;

        BufferedResponse(ClientHttpResponse delegate) throws IOException {
            this.delegate = delegate;
            try (InputStream input = delegate.getBody()) {
                this.bufferedBody = input.readAllBytes();
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
            return new ByteArrayInputStream(bufferedBody);
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}

