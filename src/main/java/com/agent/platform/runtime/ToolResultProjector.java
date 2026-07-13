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

    public ToolCallResult project(String toolCallId, ToolCallResult raw, boolean rawPersisted) {
        String content = raw.content() == null ? "" : raw.content();
        String error = raw.errorMessage() == null ? "" : raw.errorMessage();
        int contentLimit = Math.max(512, properties.getMaxToolResultCharsForModel());
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
        int count = 0;
        for (Map.Entry<String, Object> entry : rawMetadata.entrySet()) {
            if (count++ >= 32) {
                bounded.put("metadataEntriesOmitted", rawMetadata.size() - 32);
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
