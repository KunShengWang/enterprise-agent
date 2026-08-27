package com.agent.platform.ordercare.incident.application;

import com.agent.platform.ordercare.incident.config.IncidentCommandProperties;
import com.agent.platform.ordercare.incident.model.IncidentInvestigationRequest;
import com.agent.platform.ordercare.incident.model.IncidentSnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class IncidentSnapshotFactory {

    private final IncidentCommandProperties properties;
    private final ObjectMapper objectMapper;

    public IncidentSnapshotFactory(IncidentCommandProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public IncidentSnapshot create(String incidentId, IncidentInvestigationRequest request) {
        if (incidentId == null || incidentId.isBlank() || request == null) {
            throw new IllegalArgumentException("incidentId and request are required");
        }
        if (request.alertBatchId().isBlank() || request.alertType().isBlank() || request.symptom().isBlank()) {
            throw new IllegalArgumentException("alertBatchId, alertType and symptom are required");
        }
        List<String> requestIds = normalize(request.candidateRequestIds());
        if (requestIds.isEmpty() || requestIds.size() > properties.getMaxRequestIds()) {
            throw new IllegalArgumentException("candidateRequestIds must contain 1.." + properties.getMaxRequestIds());
        }
        List<String> queues = normalize(request.queueNames());
        if (queues.size() > 20) {
            throw new IllegalArgumentException("queueNames must contain at most 20 entries");
        }
        if (request.scopeSnapshotId().isBlank() && !properties.getAllowedQueues().containsAll(queues)) {
            throw new IllegalArgumentException("queueNames contain a queue outside the server whitelist");
        }

        Instant startedAt = Instant.now();
        String snapshotId = "snap-" + UUID.randomUUID();
        String scopeHash = sha256(Map.of(
                "incidentId", incidentId,
                "alertBatchId", request.alertBatchId(),
                "tenantScope", properties.getTenantScope(),
                "requestIds", requestIds,
                "queueNames", queues
        ));
        return new IncidentSnapshot(
                snapshotId,
                incidentId,
                request.alertBatchId(),
                request.alertType(),
                properties.getTenantScope(),
                new IncidentSnapshot.IncidentOrderScope(requestIds),
                new IncidentSnapshot.IncidentBusinessScope(queues),
                new IncidentSnapshot.IncidentTimeWindow(request.detectedAt().minusSeconds(300), startedAt),
                request.detectedAt(),
                startedAt,
                startedAt.plusSeconds(properties.getDeadlineSeconds()),
                scopeHash,
                request.scopeSnapshotId(),
                request.candidateFingerprint(),
                request.scopeProvenance()
        );
    }

    private List<String> normalize(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().sorted().toList();
    }

    private String sha256(Object value) {
        try {
            byte[] json = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        }
        catch (Exception exception) {
            throw new IllegalStateException("failed to hash incident scope", exception);
        }
    }
}
