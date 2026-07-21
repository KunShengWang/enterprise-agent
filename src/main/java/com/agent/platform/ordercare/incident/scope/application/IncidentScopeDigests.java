package com.agent.platform.ordercare.incident.scope.application;

import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCandidate;
import com.agent.platform.ordercare.incident.scope.model.IncidentScopeCriteria;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class IncidentScopeDigests {

    private final ObjectMapper objectMapper;

    public IncidentScopeDigests(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String criteriaDigest(IncidentScopeCriteria criteria) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("startTime", criteria.startTime());
        canonical.put("endTime", criteria.endTime());
        canonical.put("timezone", criteria.timezone());
        canonical.put("anomalyTypes", criteria.anomalyTypes().stream().map(Enum::name).sorted().toList());
        canonical.put("orderNos", criteria.orderNos().stream().sorted().toList());
        canonical.put("deductNos", criteria.deductNos().stream().sorted().toList());
        canonical.put("deadLetterIds", criteria.deadLetterIds().stream().sorted().toList());
        return sha256(objectMapper.writeValueAsString(canonical));
    }

    public String candidateFingerprint(List<IncidentScopeCandidate> candidates) {
        List<Map<String, Object>> canonical = candidates.stream()
                .sorted(Comparator.comparing(IncidentScopeCandidate::requestId)
                        .thenComparing(IncidentScopeCandidate::orderNo)
                        .thenComparing(IncidentScopeCandidate::deductNo))
                .map(candidate -> {
                    TreeMap<String, Object> value = new TreeMap<>();
                    value.put("requestId", candidate.requestId());
                    value.put("orderNo", candidate.orderNo());
                    value.put("deductNo", candidate.deductNo());
                    value.put("deadLetterIds", candidate.deadLetterIds().stream().sorted().toList());
                    value.put("queueNames", candidate.queueNames().stream().sorted().toList());
                    value.put("orderStatus", candidate.orderStatus());
                    value.put("reservationStatus", candidate.reservationStatus());
                    value.put("deductStatus", candidate.deductStatus());
                    value.put("releaseState", candidate.releaseState());
                    value.put("anomalyTypes", candidate.anomalyTypes().stream().map(Enum::name).sorted().toList());
                    value.put("relationQuality", candidate.relationQuality().name());
                    value.put("completeness", candidate.completeness());
                    return (Map<String, Object>) new LinkedHashMap<String, Object>(value);
                })
                .toList();
        return sha256(objectMapper.writeValueAsString(canonical));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot calculate incident scope digest", exception);
        }
    }
}
