package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.tool.ToolCallResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将完整工具执行结果投影为有界、明确不可信的模型上下文；原文仍由 ToolExecutionStore 保存。
 */
@Component
public class ToolResultProjector {

    private final AgentProperties properties;

    public ToolResultProjector(AgentProperties properties) {
        this.properties = properties;
    }

    /** Working-context projection only: raw store and approval/finalize contracts are untouched. */
    public ToolCallResult projectForContext(String toolCallId, ToolCallResult raw) {
        String content = raw.content() == null ? "" : raw.content();
        String projected = raw.success() ? new StructuredToolProjection().project(content) : content;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("untrustedToolData", true);
        metadata.put("rawReference", "tool-execution:" + toolCallId);
        metadata.put("contentSha256", sha256(content));
        metadata.put("projection", projected.equals(content) ? "FULL" : "STRUCTURED_LOSSLESS_FACTS");
        for (String key : java.util.List.of("manualReview", "policyAction", "validation", "errorType", "retryable"))
            if (raw.metadata().containsKey(key)) metadata.put(key, raw.metadata().get(key));
        return new ToolCallResult(raw.toolName(), raw.success(), projected, raw.errorMessage(), metadata);
    }

    public ToolCallResult project(String toolCallId, ToolCallResult raw, boolean rawPersisted) {
        String content = raw.content() == null ? "" : raw.content();
        String error = raw.errorMessage() == null ? "" : raw.errorMessage();
        // ① 限制内容长度（默认 512 字符）
        int contentLimit = Math.max(512, properties.getMaxToolResultCharsForModel());
        // ② 限制错误信息长度（默认 256 字符）
        int errorLimit = Math.max(256, properties.getMaxToolErrorCharsForModel());
        boolean contentTruncated = content.length() > contentLimit;
        boolean errorTruncated = error.length() > errorLimit;

        LinkedHashMap<String, Object> metadata = boundedMetadata(raw.metadata());
        metadata.put("untrustedToolData", true);
        metadata.put("originalContentChars", content.length());
        metadata.put("contentSha256", sha256(content));
        metadata.put("truncated", contentTruncated || errorTruncated);
        if (contentTruncated && rawPersisted) {
            metadata.put("rawReference", "tool-execution:" + toolCallId);
        }
        if (contentTruncated) {
            metadata.put("omittedContentChars", content.length() - contentLimit);
        }

        return new ToolCallResult(
                raw.toolName(),
                raw.success(),
                boundedExcerpt(content, contentLimit),
                boundedExcerpt(error, errorLimit),
                metadata
        );
    }

    private LinkedHashMap<String, Object> boundedMetadata(Map<String, Object> rawMetadata) {
        LinkedHashMap<String, Object> bounded = new LinkedHashMap<>();
        Map<String, Object> visible = new LinkedHashMap<>(rawMetadata);
        visible.keySet().removeIf(key -> key.startsWith("_harness"));
        visible = Map.copyOf(visible);
        int count = 0;
        for (Map.Entry<String, Object> entry : visible.entrySet()) {
            if (count++ >= 32) {
                bounded.put("metadataEntriesOmitted", visible.size() - 32);
                break;
            }
            String key = boundedExcerpt(String.valueOf(entry.getKey()), 128);
            Object value = entry.getValue();
            if (value == null || value instanceof Number || value instanceof Boolean) {
                bounded.put(key, value == null ? "null" : value);
            }
            else {
                bounded.put(key, boundedExcerpt(String.valueOf(value), 512));
            }
        }
        return bounded;
    }

    private String boundedExcerpt(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        String marker = "\n...[tool result truncated; load rawReference for the full value]...\n";
        int available = Math.max(0, limit - marker.length());
        int head = available * 2 / 3;
        int tail = available - head;
        return value.substring(0, head) + marker + value.substring(value.length() - tail);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
