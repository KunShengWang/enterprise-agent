package com.agent.platform.ordercare.incident.recovery.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryPlanDigestTests {

    private final RecoveryPlanDigest digest = new RecoveryPlanDigest(new ObjectMapper());

    @Test
    void mapInsertionOrderDoesNotChangeDigest() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("b", List.of(2, 1));
        left.put("a", Map.of("time", Instant.parse("2026-07-19T00:00:00Z")));
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("a", Map.of("time", Instant.parse("2026-07-19T00:00:00Z")));
        right.put("b", List.of(2, 1));

        assertEquals(digest.sha256(left), digest.sha256(right));
    }
}
