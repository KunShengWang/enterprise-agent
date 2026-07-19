package com.agent.platform.ordercare.incident.recovery.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.time.temporal.TemporalAccessor;

@Component
public class RecoveryPlanDigest {

    private final ObjectMapper objectMapper;

    public RecoveryPlanDigest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sha256(Object value) {
        try {
            byte[] canonical = objectMapper.writeValueAsString(canonicalize(value))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> || value instanceof TemporalAccessor) {
            return String.valueOf(value);
        }
        return canonicalize(objectMapper.convertValue(value, Map.class));
    }
}
